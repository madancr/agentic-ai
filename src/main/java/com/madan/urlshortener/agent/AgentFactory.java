package com.madan.urlshortener.agent;

import com.madan.urlshortener.agent.audit.AuditLog;
import com.madan.urlshortener.agent.guardrails.ConfirmationHandler;
import com.madan.urlshortener.agent.guardrails.Guardrails;
import com.madan.urlshortener.agent.llm.ClaudeLlmClient;
import com.madan.urlshortener.agent.llm.LlmClient;
import com.madan.urlshortener.agent.llm.OfflineLlmClient;
import com.madan.urlshortener.agent.tools.ShortenerApiClient;
import com.madan.urlshortener.agent.tools.ToolRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Wires the agent to a running URL-shortener service: tools, guardrails, audit log, LLM choice. */
public class AgentFactory {

    public static final int MAX_TOOL_CALLS_PER_REQUEST = 6;
    public static final int MAX_AGENT_ITERATIONS = 5;
    public static final Set<String> DENIED_DOMAINS = Set.of("phishing.example", "malware.example");

    public final ShortenerApiClient api;
    public final ToolRegistry tools;
    public final Guardrails guardrails;
    public final AuditLog audit;

    public AgentFactory(String serviceBaseUrl, Path auditFile) throws IOException {
        this.api = new ShortenerApiClient(serviceBaseUrl);
        this.tools = ToolRegistry.standard(api);
        this.guardrails = new Guardrails(tools, MAX_TOOL_CALLS_PER_REQUEST, DENIED_DOMAINS);
        this.audit = new AuditLog(auditFile);
    }

    public Agent newAgent(LlmClient llm, ConfirmationHandler confirmations) {
        return new Agent(llm, tools, guardrails, confirmations, audit, MAX_AGENT_ITERATIONS);
    }

    /** Real Claude if ANTHROPIC_API_KEY is set (and offline not forced); otherwise the offline planner. */
    public static LlmClient chooseLlm(boolean forceOffline) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank() && !forceOffline) {
            return new ClaudeLlmClient(key, System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-sonnet-5"));
        }
        return new OfflineLlmClient();
    }
}
