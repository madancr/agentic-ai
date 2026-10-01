package com.madan.urlshortener;

import com.madan.urlshortener.agent.Agent;
import com.madan.urlshortener.agent.AgentFactory;
import com.madan.urlshortener.agent.audit.AuditLog;
import com.madan.urlshortener.agent.guardrails.ConfirmationHandler;
import com.madan.urlshortener.agent.guardrails.GuardrailDecision;
import com.madan.urlshortener.agent.guardrails.GuardrailDecision.Verdict;
import com.madan.urlshortener.agent.json.Json;
import com.madan.urlshortener.agent.llm.ClaudeLlmClient;
import com.madan.urlshortener.agent.llm.LlmClient;
import com.madan.urlshortener.agent.llm.OfflineLlmClient;
import com.madan.urlshortener.agent.llm.ScriptedLlmClient;
import com.madan.urlshortener.agent.tools.ShortenerApiClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tests for the LLM-agent layer, in the same dependency-free style as AllTests.
 * Starts the real App on a free port, so the agent is tested against the actual REST API.
 *
 * Run with:  java -cp out com.madan.urlshortener.AgentTests
 */
public class AgentTests {

    /** A test body that may throw checked exceptions (HTTP calls, file I/O). */
    private interface Body {
        void run() throws Exception;
    }

    private int passed = 0;
    private int failed = 0;
    private App.Running app;
    private AgentFactory factory;
    private Path auditFile;

    public static void main(String[] args) throws Exception {
        AgentTests runner = new AgentTests();
        runner.run();
        if (runner.failed > 0) {
            System.exit(1);
        }
    }

    private void run() throws Exception {
        app = App.start(0);
        auditFile = Files.createTempFile("agent-audit", ".jsonl");
        factory = new AgentFactory(app.baseUrl(), auditFile);
        try {
            test("guardrails: hallucinated tool is denied by the allow-list", this::testUnknownToolDenied);
            test("guardrails: bad arguments are denied by schema validation", this::testSchemaViolations);
            test("guardrails: internal / private / deny-listed destinations are blocked", this::testDestinationPolicy);
            test("guardrails: custom alias needs human confirmation", this::testAliasNeedsConfirmation);
            test("guardrails: tool-call budget per request", this::testBudget);
            test("agent: multi-step request chains shorten -> stats via the REST API", this::testMultiStep);
            test("agent: follow-up question uses conversation memory", this::testFollowUpMemory);
            test("agent: analytics tool reports top referrers", this::testAnalytics);
            test("agent: declined confirmation means nothing is created", this::testDeclinedConfirmation);
            test("agent: misbehaving model is contained and audited", this::testMisbehavingModel);
            test("audit: tampering with the log is detected", this::testAuditTamper);
            test("api client: HTTP 429 from RateLimiter is retried once after Retry-After", this::testRateLimitRetry);
            test("claude client: request carries all three tool schemas", this::testClaudeRequestBody);
            test("json: nested round trip", this::testJsonRoundTrip);
        } finally {
            app.stop(0);
            Files.deleteIfExists(auditFile);
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
    }

    private void test(String name, Body body) {
        try {
            body.run();
            System.out.println("PASS - " + name);
            passed++;
        } catch (Throwable t) {
            System.out.println("FAIL - " + name + " :: " + t);
            failed++;
        }
    }

    private Agent agent(LlmClient llm, boolean approve) {
        return factory.newAgent(llm, ConfirmationHandler.scripted((tool, input) -> approve));
    }

    private GuardrailDecision check(String tool, Map<String, Object> input) {
        return factory.guardrails.check(tool, input, 0);
    }

    // ---- guardrails ---------------------------------------------------

    private void testUnknownToolDenied() {
        GuardrailDecision d = check("delete_link", Map.of("code", "abc"));
        assertEquals(Verdict.DENY, d.verdict(), "verdict");
        assertEquals("allow_list", d.rule(), "rule");
    }

    private void testSchemaViolations() {
        assertEquals("schema", check("shorten_url", Map.of()).rule(), "missing url");
        assertEquals("schema", check("shorten_url", Map.of("url", "https://e.com", "admin", true)).rule(), "unknown arg");
        assertEquals("schema", check("shorten_url", Map.of("url", "https://e.com", "ttl_seconds", 5L)).rule(), "ttl range");
        assertEquals("schema", check("shorten_url", Map.of("url", "https://e.com", "alias", "bad alias")).rule(), "alias pattern");
        assertEquals("schema", check("get_link_stats", Map.of("code", 42L)).rule(), "code type");
    }

    private void testDestinationPolicy() {
        for (String url : new String[]{"http://localhost/admin", "http://127.0.0.1:8080", "http://10.1.2.3/x",
                "http://192.168.1.1", "http://169.254.169.254/latest/meta-data", "https://jira.corp/x",
                "https://login.phishing.example/reset"}) {
            assertEquals("destination_policy", check("shorten_url", Map.of("url", url)).rule(), url);
        }
        assertEquals(Verdict.ALLOW, check("shorten_url", Map.of("url", "https://www.example.com")).verdict(), "public URL");
    }

    private void testAliasNeedsConfirmation() {
        GuardrailDecision d = check("shorten_url", Map.of("url", "https://www.example.com", "alias", "launch"));
        assertEquals(Verdict.NEEDS_CONFIRMATION, d.verdict(), "verdict");
    }

    private void testBudget() {
        assertEquals("budget", factory.guardrails.check("get_link_stats", Map.of("code", "abc"), 6).rule(), "rule");
    }

    // ---- agent against the real service --------------------------------

    private void testMultiStep() throws Exception {
        Agent a = agent(new OfflineLlmClient(), true);
        String answer = a.handle("Shorten https://www.example.com/report-a and tell me how many clicks it has.");
        assertTrue(answer.contains(app.baseUrl() + "/"), "answer should contain the short URL: " + answer);
        assertTrue(answer.contains("0 clicks"), "answer should report clicks: " + answer);
        assertEquals(2, a.getMetrics().toolCallsExecuted, "tool calls");
        assertEquals(3, a.getMetrics().llmCalls, "LLM calls (plan -> chain -> answer)");
    }

    private void testFollowUpMemory() throws Exception {
        Agent a = agent(new OfflineLlmClient(), true);
        a.handle("Shorten https://www.example.com/report-b");
        String answer = a.handle("How many clicks does it have?");
        assertTrue(answer.contains("0 clicks"), answer);
    }

    private void testAnalytics() throws Exception {
        Agent a = agent(new OfflineLlmClient(), true);
        a.handle("Shorten https://www.example.com/campaign as camp-1");
        factory.api.visit("camp-1", "https://news.example");
        factory.api.visit("camp-1", "https://news.example");
        String answer = a.handle("Show me the analytics for camp-1");
        assertTrue(answer.contains("2 clicks total"), answer);
        assertTrue(answer.contains("https://news.example (2)"), answer);
    }

    private void testDeclinedConfirmation() throws Exception {
        Agent a = agent(new OfflineLlmClient(), false);
        String answer = a.handle("Shorten https://www.example.com/promo as promo-x");
        assertTrue(answer.contains("declined"), answer);
        assertEquals(0, a.getMetrics().toolCallsExecuted, "nothing executed");
        assertTrue(a.handle("Where does promo-x go?").contains("no short link"), "alias must not exist");
    }

    private void testMisbehavingModel() throws Exception {
        Agent a = agent(new ScriptedLlmClient(List.of(
                ScriptedLlmClient.toolCalls("delete_link", Map.of("code", "x")),
                ScriptedLlmClient.toolCalls("get_link_stats", Map.of("code", "abc"),
                        "get_link_stats", Map.of("code", "abc")))), true);
        String answer = a.handle("do something");
        assertTrue(answer.contains("stopped after 5 steps"), answer);
        assertEquals(1, a.getMetrics().stepLimitStops, "safe stops");
        assertTrue(a.getMetrics().deniedByPolicy >= 2, "hallucinated tool and budget overrun must be denied");
        assertEquals(null, AuditLog.verify(auditFile), "audit chain");
    }

    private void testAuditTamper() throws Exception {
        Path copy = Files.createTempFile("tampered", ".jsonl");
        try {
            List<String> lines = Files.readAllLines(auditFile);
            assertTrue(lines.size() > 2, "audit log should have records");
            lines.set(1, lines.get(1).replace("\"ALLOW\"", "\"DENY\"").replace("\"ok\"", "\"error\""));
            Files.write(copy, lines);
            assertTrue(AuditLog.verify(copy) != null, "tampering must be detected");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    private void testRateLimitRetry() throws Exception {
        // Exhaust the 20-token burst of the service's RateLimiter, then make one more call:
        // it gets 429 + Retry-After, the client waits (bucket refills at 10/s) and retries once.
        ShortenerApiClient api = new ShortenerApiClient(app.baseUrl());
        int throttled = 0;
        for (int i = 0; i < 25; i++) {
            if (rawShortenStatus(i) == 429) throttled++;
        }
        assertTrue(throttled > 0, "burst should have been throttled by the service");
        ShortenerApiClient.ApiResponse resp = api.shorten("https://www.example.com/after-burst", null, null);
        assertEquals(201, resp.status(), "retried call should succeed");
    }

    /** Raw POST without the client's retry, used to drain the rate limiter's bucket. */
    private int rawShortenStatus(int i) throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(app.baseUrl() + "/api/shorten"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"longUrl\":\"https://e.com/burst" + i + "\"}"))
                .build();
        return http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @SuppressWarnings("unchecked")
    private void testClaudeRequestBody() {
        String body = new ClaudeLlmClient("key", "claude-sonnet-5").buildRequestBody("sys",
                List.of(Map.of("role", "user", "content", "hi")), factory.tools.definitions());
        Map<String, Object> req = Json.parseObject(body);
        List<Map<String, Object>> defs = (List<Map<String, Object>>) req.get("tools");
        assertEquals(List.of("shorten_url", "get_link_stats", "get_link_analytics"),
                defs.stream().map(d -> d.get("name")).toList(), "tool names");
        assertTrue(((Map<String, Object>) defs.get(0).get("input_schema")).containsKey("properties"), "schema present");
    }

    private void testJsonRoundTrip() {
        String text = "{\"a\":1,\"b\":[true,\"x\\\"y\\n\"],\"c\":{\"d\":2.5}}";
        assertEquals(text, Json.write(Json.parseObject(text)), "round trip");
    }

    // ---- tiny assertions (same idea as AllTests) ----------------------

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
