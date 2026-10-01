# URL Shortener 


All the requirement details, Architecture design, API contracts are Extensively covered in the enclosed PDF document
Agentic_URL_Shortner.pdf

A working URL shortener built with nothing but the JDK — no Spring, no
Maven/Gradle dependency downloads required. It compiles and runs with just
`javac` and `java`, which makes it easy to drop into any machine 

## Codebase Reasoning:
======================

- `POST /api/shorten` — shorten a long URL, optionally with a custom alias
  and/or a time-to-live. Rate limited per client IP.
- `GET /{code}` — 302-redirects to the original URL and records a click
  (including referrer and user-agent, for analytics).
- `GET /api/stats/{code}` — metadata and lifetime click count, without
  consuming a "click" (doesn't redirect).
- `GET /api/analytics/{code}` — clicks in the last 24h/7d, a per-day
  breakdown, and the top 5 referrers driving traffic to the link.
- `GET /healthz` — liveness/readiness probe plus a few runtime metrics
  (uptime, total codes, total lifetime clicks).

## LLM agent layer (new)

The service can now also be driven by an **LLM agent**. A user types a plain-English request ("shorten this
as spring-launch for 7 days, then show me its analytics"). The model decides which **tools** to call. The
Java code under `agent/` validates each call (guardrails, human approval for custom aliases), executes it
against the **existing REST API**, and records it in a tamper-evident audit log. The service code is
unchanged. The agent is just another HTTP client, subject to the same validation and the same per-IP rate
limiter. The only change to existing code is that `App.main` was refactored into `App.start(port)`, so
the server can be started from the demo and the tests. Running `App` behaves exactly as before.

| Tool (what the model sees) | Calls this existing endpoint |
|---|---|
| `shorten_url(url, alias?, ttl_seconds?)` | `POST /api/shorten` |
| `get_link_stats(code)` | `GET /api/stats/{code}` (read-only, no click counted) |
| `get_link_analytics(code)` | `GET /api/analytics/{code}` |


## Running it

```bash
# from the project root
javac -d out $(find src/main -name "*.java") $(find src/test -name "*.java")

# run the test suite (no JUnit needed)
java -cp out com.madan.urlshortener.AllTests

# start the server (defaults to port 8080; override with an arg or $PORT)
java -cp out com.madan.urlshortener.App 8080

# LLM agent: scripted walkthrough, interactive chat, and its tests (14)
java -cp out com.madan.urlshortener.agent.AgentDemo
java -cp out com.madan.urlshortener.agent.AgentChat
java -cp out com.madan.urlshortener.AgentTests
```

In IntelliJ, all five are in the Run dropdown (App, AllTests, AgentDemo, AgentChat, AgentTests).

## Trying it out

```bash
# Shorten a URL
curl -X POST http://localhost:8080/api/shorten \
  -d '{"longUrl":"https://example.com/some/very/long/path"}'
# => {"shortCode":"4c92","shortUrl":"http://localhost:8080/4c92", ...}

# Shorten with a custom alias and a 1-hour expiry
curl -X POST http://localhost:8080/api/shorten \
  -d '{"longUrl":"https://example.com/resume","customAlias":"my-resume","ttlSeconds":3600}'

# Follow the short link
curl -i http://localhost:8080/4c92

# Check stats without triggering a redirect
curl http://localhost:8080/api/stats/4c92

# See how a link is performing
curl http://localhost:8080/api/analytics/4c92
# => {"totalClicks":4,"clicksLast24h":4,"clicksLast7d":4,
#     "dailyBreakdown":{"2026-09-27":4},
#     "topReferrers":[{"referrer":"https://twitter.com/x","clicks":2}, ...]}

# Liveness / basic metrics
curl http://localhost:8080/healthz
```

## Project layout

```
src/main/java/com/madan/urlshortener/
  model/UrlEntry.java          - the stored record (url, timestamps, click tracking)
  model/ClickEvent.java        - one recorded redirect (timestamp, referrer, user-agent)
  model/AnalyticsSummary.java  - aggregated view returned by /api/analytics/{code}
  util/Base62.java             - counter -> short-code encoding
  store/UrlRepository.java     - storage layer (in-memory today; swappable)
  service/UrlShortenerService.java  - all business rules live here
  reliability/RateLimiter.java         - per-key token-bucket limiter
  reliability/RequestLoggingFilter.java - structured (JSON-line) access logging
  http/                        - com.sun.net.httpserver wiring + hand-rolled JSON
  App.java                     - wires it together, starts the server, handles graceful shutdown
  agent/                       - NEW: LLM agent that uses the REST API through tools (see AGENT.md)
    Agent.java                 -   the loop: LLM -> guardrails -> (human approval) -> tool -> audit -> LLM
    tools/                     -   shorten_url, get_link_stats, get_link_analytics + HTTP client for the API
    llm/                       -   ClaudeLlmClient (real), OfflineLlmClient (demo stand-in), ScriptedLlmClient
    guardrails/                -   allow-list, schema checks, destination policy, budget, confirmations
    audit/                     -   hash-chained audit log + agent metrics
    AgentDemo.java, AgentChat.java, AgentFactory.java
src/test/java/com/madan/urlshortener/AllTests.java    - dependency-free test runner (16 tests)
src/test/java/com/madan/urlshortener/AgentTests.java  - agent-layer tests (14), run against the real App
```

The service layer (`UrlShortenerService`) doesn't know anything about HTTP,
and the HTTP handlers don't know anything about ID generation or storage —
that separation is what let the whole thing get tested via plain `main()`
methods without spinning up a server, and it's the same seam you'd cut
along if you swapped the storage backend or the transport (e.g. adding a
gRPC or GraphQL front end later).

## Design decisions: 

**Short code generation: counter + Base62, not random + collision check.**
Two common approaches: (1) generate a random string and retry on collision,
or (2) take a monotonically increasing ID and encode it. This project uses
(2) — an `AtomicLong` counter, Base62-encoded — because it's collision-free
*by construction* (each counter value is issued exactly once), so there's
no retry loop, no wasted "guess and check" work, and no need to check the
store before accepting a code. Base62 (not Base64) is used specifically to
keep codes URL-safe and visually unambiguous (no `+`, `/`, `=`). The
trade-off: codes are sequential/guessable, which leaks the approximate
creation order and volume of your service; for a real product you'd
XOR/permute the counter (e.g. with a fixed-width Feistel cipher) before
encoding so external codes look random while remaining collision-free
internally.


**In-memory storage***  `UrlRepository` is
intentionally the only class that knows about the storage mechanism. Swap
it for:
- **Redis** — if you mainly need fast reads on hot links and can tolerate
  an occasional cache-miss fallback to a durable store.
- **A SQL table** (`short_code` PK, `long_url`, `created_at`, `expires_at`,
  `click_count`) — if you need durability and the write volume is
  moderate; index on `short_code` for O(1) lookups.
- **DynamoDB/Cassandra** — if you need to shard across nodes by
  `short_code` hash for very high write throughput.

Here for the demo purpose, we have not used any of the datastore, we have just leveraged the In-Memory HashMap for UrlRepository.

**Scaling the ID generator past one process.** The single `AtomicLong`
works great in one JVM but doesn't survive a restart (counter resets) or
scale across multiple instances (two instances would both hand out ID
1,000,000). Two standard fixes: (a) each instance leases a range of IDs
from a central counter (e.g., a row in a DB it increments by 10,000 at a
time, then hands out that whole range locally before asking for the next
one) — cheap, few DB round trips, small chance of wasted IDs on a crash;
or (b) a Snowflake-style ID (timestamp + machine ID + sequence bits packed
into a 64-bit long) generated fully locally with no coordination at all,
at the cost of longer/less clean-looking codes.

**Deduplication.** Re-shortening the exact same long URL (without a custom
alias) returns the existing short code rather than minting a new one —
this is what bit.ly and similar services do, and it keeps the store from
filling up with N different codes pointing at the same URL. It's a
reverse index (`longUrl -> shortCode`) alongside the primary one.

### Analytics

**Bounded click history, not unbounded.** Every redirect is recorded as a
`ClickEvent` (timestamp, referrer, user-agent), but `UrlEntry` only retains
the most recent `MAX_RETAINED_EVENTS` (5,000) per code — old events are
evicted once that cap is hit. The lifetime `totalClicks` counter is
separate and never trimmed, so the headline number is always exact; only
the *breakdown* (daily counts, top referrers) is subject to the retention
window, meaning an extremely hot link's oldest history within a lookback
period could theoretically have aged out. This is the right trade-off for
an in-memory demo — unbounded per-link history is an unbounded memory leak
waiting to happen on a link that goes viral. A production version would
replace the raw-event list with pre-aggregated daily counters (increment a
`Map<LocalDate, Long>` at write time instead of scanning raw events at read
time), which sidesteps the trade-off entirely and is also cheaper to query.

**Why synchronous, in-request recording instead of an event stream?**
For this scope, recording a click is cheap enough (append to a bounded
deque, increment a counter) to do inline without hurting redirect latency.
The natural next step at real scale is to fire click events onto a queue
(Kafka/Kinesis) and have a separate consumer aggregate them — that decouples
analytics write volume from the redirect hot path entirely, at the cost of
eventual (not immediate) consistency in the analytics numbers.

### Reliability

**Rate limiting: token bucket, per client IP, on the write path only.**
`/api/shorten` is rate limited (20-request burst, refilling at 10/sec) because
it's the endpoint that mints new state and is the obvious abuse target;
`GET /{code}` redirects are left unlimited since they're cheap reads and
limiting them would just as easily throttle legitimate traffic to a popular
link. Token bucket was chosen over a fixed-window counter specifically to
avoid the boundary-burst problem a fixed window has (2x traffic possible
right at the window edge). This is in-process state, which is fine for one
instance but doesn't coordinate across multiple instances behind a load
balancer — a real multi-instance deployment would move this to a shared
store (Redis `INCR`+`EXPIRE`, or a Lua script for atomicity) so all
instances see the same bucket per client.

**Structured (JSON-line) access logging + request IDs.** Every request gets
a short random request ID, returned as an `X-Request-Id` response header
and included in both the log line and any error response body. Plain-text
logs are fine to eyeball but painful to query at volume; emitting one JSON
object per request means it can be shipped as-is into any log aggregator
(CloudWatch, Datadog, ELK) and filtered by field. The request ID is what
turns "a user says their redirect failed" into "find this exact log line,"
which matters a lot more once there's more than one instance running.

**`/healthz` reports on itself only.** It never depends on an external
system (there isn't one here), which is the right principle to carry
forward: a liveness probe should fail only when *this* process is broken,
not because a downstream dependency it calls is slow — conflating the two
causes an orchestrator to kill and restart healthy instances during a
dependency outage, which usually makes things worse, not better.

**Graceful shutdown.** A JVM shutdown hook calls `server.stop(5)`, giving
in-flight requests up to 5 seconds to finish before the process exits.
Without this, a container orchestrator's SIGTERM during a routine deploy
would hard-kill requests mid-flight instead of draining them.

**What's still deliberately left out / next steps:**
- **Auth** — right now anyone can create/shorten; a real deployment would
  put an API key or session check in front of `/api/shorten` while keeping
  the redirect endpoint fully public.
- **Persistence** — everything (URLs, click history, rate-limiter state) is
  lost on restart; swapping in one of the storage backends above is the
  natural next step, and would need the rate limiter's state to move to
  Redis at the same time if running more than one instance.
- **Distributed rate limiting / ID generation** — both are called out above
  as in-process-only; see the respective sections for the multi-instance fix.
- **Custom domains** — supporting `go.acme.com/xyz` style short links
  instead of only the service's own domain is mostly a DNS + Host-header
  routing concern layered on top of what's here.

