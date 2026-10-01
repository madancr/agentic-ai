package com.madan.urlshortener.agent.audit;

/** Simple counters for the agent run (printed at the end of the demo). */
public class AgentMetrics {

    public int requests;
    public int llmCalls;
    public long inputTokens;
    public long outputTokens;
    public int toolCallsRequested;
    public int toolCallsExecuted;
    public int toolErrors;
    public int deniedByPolicy;
    public int confirmationsRequested;
    public int confirmationsApproved;
    public int stepLimitStops;
    public long totalToolLatencyMs;

    public String summary() {
        double avg = toolCallsExecuted == 0 ? 0 : (double) totalToolLatencyMs / toolCallsExecuted;
        int ok = toolCallsExecuted - toolErrors;
        double successRate = toolCallsExecuted == 0 ? 0 : 100.0 * ok / toolCallsExecuted;
        return String.format("""
                  requests handled ........... %d
                  LLM calls .................. %d  (tokens in/out: %d / %d)
                  tool calls requested ....... %d
                    denied by guardrails ..... %d
                    human confirmations ...... %d asked, %d approved
                    executed ................. %d  (success %.0f%%, errors %d, avg latency %.1f ms)
                  step-limit safe stops ...... %d""",
                requests, llmCalls, inputTokens, outputTokens, toolCallsRequested, deniedByPolicy,
                confirmationsRequested, confirmationsApproved, toolCallsExecuted, successRate, toolErrors, avg,
                stepLimitStops);
    }
}
