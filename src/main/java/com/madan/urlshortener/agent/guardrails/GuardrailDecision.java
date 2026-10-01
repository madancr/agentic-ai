package com.madan.urlshortener.agent.guardrails;

/**
 * Outcome of checking one tool call BEFORE it runs.
 * {@code rule} names the policy that produced the decision, so the audit log explains every outcome.
 */
public record GuardrailDecision(Verdict verdict, String rule, String reason) {

    public enum Verdict { ALLOW, DENY, NEEDS_CONFIRMATION }

    public static GuardrailDecision allow() {
        return new GuardrailDecision(Verdict.ALLOW, "allowed", "within policy");
    }

    public static GuardrailDecision deny(String rule, String reason) {
        return new GuardrailDecision(Verdict.DENY, rule, reason);
    }

    public static GuardrailDecision confirm(String reason) {
        return new GuardrailDecision(Verdict.NEEDS_CONFIRMATION, "human_confirmation", reason);
    }
}
