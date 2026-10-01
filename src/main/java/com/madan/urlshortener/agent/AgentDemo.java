package com.madan.urlshortener.agent;

import com.madan.urlshortener.App;
import com.madan.urlshortener.agent.audit.AuditLog;
import com.madan.urlshortener.agent.guardrails.ConfirmationHandler;
import com.madan.urlshortener.agent.llm.LlmClient;
import com.madan.urlshortener.agent.llm.ScriptedLlmClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Scripted walkthrough for the interview - just press Run. No API key needed (uses the offline planner
 * unless ANTHROPIC_API_KEY is set). Starts the normal App (same routes, rate limiter, access log) in-process.
 *
 * Part 1: a well-behaved agent uses the EXISTING REST API through tools: a multi-step request, dedupe,
 *         a human approval (and a decline), analytics with referrers, a policy block, and service errors.
 * Part 2: a MISBEHAVING model (hallucinated tool, bad arguments, phishing domain, endless tool calls)
 *         is contained by the guardrails, the tool-call budget and the step limit.
 *
 * Lines starting with {"ts": ...} are the service's own JSON access log (RequestLoggingFilter); their
 * requestId matches "serviceRequestId" in agent-audit.jsonl, so every agent action is traceable end-to-end.
 */
public class AgentDemo {

    public static void main(String[] args) throws Exception {
        try (App.Running app = App.start(8080)) {
            AgentFactory factory = new AgentFactory(app.baseUrl(), Path.of("agent-audit.jsonl"));
            LlmClient llm = AgentFactory.chooseLlm(args.length > 0 && args[0].equals("--offline"));
            banner("PART 1 - agent using the URL shortener's REST API through tools   (brain: " + llm.name()
                    + ", service: " + app.baseUrl() + ")");

            // Scripted human reviewer: approves the spring-launch alias, declines the promo alias.
            ConfirmationHandler reviewer = ConfirmationHandler.scripted(
                    (tool, input) -> !String.valueOf(input.get("alias")).startsWith("promo"));
            Agent agent = factory.newAgent(llm, reviewer);

            ask(agent, "Shorten https://www.example.com/research/q3-market-outlook and tell me how many clicks it has.");
            ask(agent, "Shorten https://www.example.com/research/q3-market-outlook again please.");
            ask(agent, "Create a short link for https://www.example.com/events/spring-launch as spring-launch, "
                    + "expiring in 7 days.");

            System.out.println("(three people open " + app.baseUrl() + "/spring-launch -> HTTP "
                    + factory.api.visit("spring-launch", "https://www.linkedin.com/feed") + ", "
                    + factory.api.visit("spring-launch", "https://www.linkedin.com/feed") + ", "
                    + factory.api.visit("spring-launch", "https://twitter.com/x") + ")\n");

            ask(agent, "Show me the traffic analytics for spring-launch.");
            ask(agent, "Where does spring-launch go?");
            ask(agent, "Shorten http://localhost:8080/admin/export so I can share it with a vendor.");
            ask(agent, "Shorten https://www.example.com/other-page as spring-launch.");
            ask(agent, "Shorten https://www.example.com/promo as promo-2026.");
            ask(agent, "Shorten ftp://files.example.com/report.pdf");
            ask(agent, "Delete the spring-launch link.");
            System.out.println("Metrics (part 1):\n" + agent.getMetrics().summary());

            banner("PART 2 - a misbehaving model is contained by the guardrails");
            LlmClient rogue = new ScriptedLlmClient(List.of(
                    ScriptedLlmClient.toolCalls("delete_link", Map.of("code", "spring-launch")),
                    ScriptedLlmClient.toolCalls("shorten_url",
                            Map.of("url", "https://www.example.com", "ttl_seconds", -5L, "is_admin", true)),
                    ScriptedLlmClient.toolCalls("shorten_url", Map.of("url", "https://login.phishing.example/reset")),
                    ScriptedLlmClient.toolCalls("get_link_stats", Map.of("code", "spring-launch"),
                            "get_link_stats", Map.of("code", "spring-launch"))));
            Agent rogueAgent = factory.newAgent(rogue, reviewer);
            ask(rogueAgent, "Clean up old links.");
            System.out.println("Metrics (part 2):\n" + rogueAgent.getMetrics().summary());

            banner("AUDIT");
            String broken = AuditLog.verify(factory.audit.getFile());
            System.out.println("Every request, tool call, guardrail decision and answer is in "
                    + factory.audit.getFile().toAbsolutePath());
            System.out.println("Hash chain: " + (broken == null ? "verified (tamper-evident)" : "BROKEN - " + broken));
        }
    }

    private static void ask(Agent agent, String request) throws Exception {
        System.out.println("You> " + request);
        System.out.println("Agent> " + agent.handle(request) + "\n");
    }

    private static void banner(String title) {
        System.out.println("\n" + "=".repeat(100) + "\n" + title + "\n" + "=".repeat(100));
    }
}
