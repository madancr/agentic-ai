# LLM agent layer: walkthrough

This adds an LLM agent that uses the URL shortener **through its existing REST API**. The model never executes anything itself. It can only *ask* for a tool by name, with JSON arguments. Our Java code decides whether the call runs, runs it, records it, and sends the result back.

## Run it (IntelliJ)

Pick one of these from the Run dropdown:

- **AgentDemo**: scripted walkthrough (about 2 seconds, no API key needed).
- **AgentChat**: type requests yourself, e.g. `shorten https://www.example.com/page as my-page, expiring in 2 days`, `how many clicks does my-page have?`, `show me the analytics for my-page`. Custom aliases ask you to approve (`y/n`).
- **AgentTests**: 14 tests against the real `App`. **AllTests**: your original 16 still pass.

## How it fits the existing design

```
 User: "Shorten https://... as spring-launch for 7 days, then show me its analytics"
            |
            v
 +---------------------------- agent/Agent (the loop) -----------------------------+
 |  1. send conversation + tool definitions to the LLM                              |
 |  2. LLM replies with text -> done, or with tool_use -> for EACH call:            |
 |       Guardrails.check(): allow-list | budget | JSON schema | destination policy |
 |         NEEDS_CONFIRMATION -> human says y/n  (custom alias = public namespace)  |
 |         DENY  -> error result back to the LLM, nothing runs                      |
 |         ALLOW -> Tool.execute() -> ShortenerApiClient --HTTP-->                  |
 |       AuditLog.record() (hash-chained; includes the server's X-Request-Id)       |
 |  3. tool results go back to the LLM -> repeat (max 5 iterations, 6 tool calls)   |
 +----------------------------------------------------------------------------------+
            | HTTP (same path as curl or a browser)
            v
   RequestLoggingFilter -> ShortenHandler (RateLimiter) / StatsHandler / AnalyticsHandler
            -> UrlShortenerService -> UrlRepository            (existing code, unchanged)
```

**Why go through HTTP rather than calling `UrlShortenerService` directly?** The agent is just another client. It gets exactly the same validation, the same per-IP token-bucket rate limiter and the same access logging as everyone else, and it cannot bypass any business rule. The service can be deployed and scaled separately. This is the same "separation of layers" argument the README makes for swapping transports.

## The exact JSON (Claude Messages API, tool use)

1. The request carries the tool definitions (from `ToolRegistry.definitions()`):
   ```json
   {"model":"claude-sonnet-5","system":"You are a URL-shortener assistant...",
    "tools":[{"name":"shorten_url","description":"Create a short link for a long http(s) URL...",
              "input_schema":{"type":"object",
                "properties":{"url":{"type":"string"},"alias":{"type":"string","pattern":"^[A-Za-z0-9_-]{3,32}$"},
                              "ttl_seconds":{"type":"integer","minimum":60}},
                "required":["url"],"additionalProperties":false}},
             {"name":"get_link_stats",...},{"name":"get_link_analytics",...}],
    "messages":[{"role":"user","content":"Shorten https://www.example.com/report and tell me how many clicks it has"}]}
   ```
2. The model asks for a tool (`stop_reason: "tool_use"`):
   ```json
   {"content":[{"type":"text","text":"I'll create that short link."},
               {"type":"tool_use","id":"toolu_01","name":"shorten_url","input":{"url":"https://www.example.com/report"}}],
    "stop_reason":"tool_use"}
   ```
3. We run it and return the result as the next user message:
   ```json
   {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01",
     "content":"{\"shortCode\":\"4c92\",\"shortUrl\":\"http://localhost:8080/4c92\",...,\"requestId\":\"beff0b9e\"}",
     "is_error":false}]}
   ```
4. The model decides it still needs the click count, so it calls `get_link_stats` with `4c92`. This multi-step plan is its own. It then answers in text (`stop_reason: "end_turn"`).

## Code guide (read in this order)

| Class | Responsibility |
|---|---|
| `agent/tools/Tool` | Interface: `name`, `description` (what the model reads), `inputSchema` (JSON Schema), `execute`, optional `confirmationReason`. |
| `agent/tools/ShortenUrlTool`, `GetLinkStatsTool`, `GetLinkAnalyticsTool` | One per existing endpoint. Custom alias → needs human confirmation. |
| `agent/tools/ShortenerApiClient` | `java.net.http` client for the API. Retries a **429** from your `RateLimiter` **once** after `Retry-After` (capped at 2 s). Captures `X-Request-Id`. |
| `agent/tools/ApiResults` | Maps your `{"error": "..."}` + HTTP status to stable codes the model can reason about (`409 → alias_taken`, `410 → expired`, `429 → rate_limited`). |
| `agent/llm/LlmClient` | One interface, three implementations: `ClaudeLlmClient` (real API, bounded retry with backoff on 429/5xx/529), `OfflineLlmClient` (demo), `ScriptedLlmClient` (a deliberately misbehaving model). |
| `agent/guardrails/Guardrails` | Checks every call *before* it runs (table below). |
| `agent/Agent` | The loop: conversation memory, guardrails, confirmation, execution, audit, metrics, step limit. |
| `agent/audit/AuditLog` | JSON lines, each holding the SHA-256 of the previous line. `verify()` detects edits, deletions or reordering. |
| `agent/json/Json` | Nested JSON parser. Your `JsonUtil` is intentionally flat-only; Claude responses are nested. |
| `App.start(port)` | The only change to existing code: `main()`'s wiring moved into a method that returns a stoppable handle. |

## Guardrails: the model proposes, Java decides

| Check | Why | In the demo |
|---|---|---|
| Tool allow-list | Models can hallucinate tools (`delete_link`) | Part 2 |
| JSON-schema validation of arguments | Model output is untrusted input | Part 2: `ttl_seconds: -5`, `is_admin: true` |
| Destination policy | Never publish short links to internal hosts (localhost, 10.x, 192.168.x, 169.254.169.254 cloud metadata, `.corp`) or deny-listed domains | Part 1 (localhost), Part 2 (phishing domain) |
| Human confirmation | A custom alias is a public, first-come namespace (brand risk) | `spring-launch` approved, `promo-2026` declined |
| Tool-call budget (6) + iteration limit (5) | Runaway loops and cost | Part 2 ends with a safe stop |
| Service-side validation, still | Defence in depth: `ftp://` passes the guardrail but the service returns 400 | Part 1 |
| Audit + metrics | Every decision explained (rule + reason) and tamper-evident | End of demo |
