package com.madan.urlshortener.agent;

import com.madan.urlshortener.App;
import com.madan.urlshortener.agent.audit.AuditLog;
import com.madan.urlshortener.agent.guardrails.ConfirmationHandler;
import com.madan.urlshortener.agent.llm.LlmClient;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

/**
 * Interactive mode: starts the URL shortener (same App as always) and lets you chat with the agent.
 *
 *   Run in IntelliJ, then type requests such as:
 *     shorten https://www.example.com/some/long/page as my-page, expiring in 2 days
 *     how many clicks does my-page have?
 *     show me the analytics for my-page
 *   Type "exit" to quit. Program argument --offline forces the offline planner even if a key is set.
 *   Program argument --url=http://host:port talks to an already-running service instead of starting one.
 */
public class AgentChat {

    public static void main(String[] args) throws Exception {
        boolean offline = Arrays.asList(args).contains("--offline");
        String external = Arrays.stream(args).filter(a -> a.startsWith("--url=")).map(a -> a.substring(6))
                .findFirst().orElse(null);
        Scanner in = new Scanner(System.in);

        App.Running app = external == null ? App.start(8080) : null;
        String baseUrl = external != null ? external : app.baseUrl();
        try {
            AgentFactory factory = new AgentFactory(baseUrl, Path.of("agent-audit.jsonl"));
            LlmClient llm = AgentFactory.chooseLlm(offline);
            Agent agent = factory.newAgent(llm, ConfirmationHandler.console(in));
            System.out.println("URL shortener at " + baseUrl + "  (agent brain: " + llm.name() + ")");
            System.out.println("Ask me to shorten a URL, look one up, report clicks or analytics. Type 'exit' to quit.\n");
            while (true) {
                System.out.print("You> ");
                System.out.flush();
                if (!in.hasNextLine()) break;
                String line = in.nextLine().trim();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) break;
                if (line.isEmpty()) continue;
                System.out.println("Agent> " + agent.handle(line) + "\n");
            }
            System.out.println("\nSession metrics:\n" + agent.getMetrics().summary());
            String broken = AuditLog.verify(factory.audit.getFile());
            System.out.println("Audit log " + factory.audit.getFile().toAbsolutePath() + ": "
                    + (broken == null ? "hash chain verified" : "TAMPERED - " + broken));
        } finally {
            if (app != null) app.stop(0);
        }
    }
}
