package com.madan.urlshortener.agent.guardrails;

import java.util.Map;
import java.util.Scanner;
import java.util.function.BiPredicate;

/** Asks a human to approve a high-impact action. */
public interface ConfirmationHandler {

    boolean confirm(String toolName, Map<String, Object> input, String reason);

    /** Interactive: asks on the console (works in the IntelliJ Run window). */
    static ConfirmationHandler console(Scanner in) {
        return (tool, input, reason) -> {
            System.out.print("  [approval needed] " + reason + " - approve? (y/n): ");
            System.out.flush();
            return in.hasNextLine() && in.nextLine().trim().toLowerCase().startsWith("y");
        };
    }

    /** Scripted: used by the demo and tests; prints the question and the scripted answer. */
    static ConfirmationHandler scripted(BiPredicate<String, Map<String, Object>> answer) {
        return (tool, input, reason) -> {
            boolean ok = answer.test(tool, input);
            System.out.println("  [approval needed] " + reason + " - approve? " + (ok ? "y" : "n"));
            return ok;
        };
    }
}
