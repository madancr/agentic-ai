package com.madan.urlshortener.agent.llm;

import com.madan.urlshortener.agent.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An offline stand-in for the LLM so the demo runs without an API key or network.
 *
 * It is a small rule-based planner, NOT a real model, but it speaks exactly the same protocol as Claude:
 * it reads the conversation, returns tool_use blocks, reads tool_result blocks, chains a follow-up call
 * when needed (shorten -> then check the new link's stats) and writes the final answer from the results.
 * The agent loop, guardrails and tools cannot tell the difference, so everything else is exercised
 * exactly as it would be with the real model.
 */
public class OfflineLlmClient implements LlmClient {

    private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?|ftp|file|javascript|data):[^\\s,;\"'<>]+)");
    private static final Pattern ALIAS = Pattern.compile("(?i)\\b(?:as|alias|named|called)\\s+[\"']?([A-Za-z0-9_-]{3,32})");
    private static final Pattern TTL = Pattern.compile(
            "(?i)(?:expir\\w*|valid|lasting)\\s+(?:in\\s+|for\\s+|after\\s+)?(\\d+)\\s*(minute|min|hour|hr|day|week)s?");
    private static final Pattern[] CODE_PATTERNS = {
            Pattern.compile("(?i)where\\s+(?:does|do)\\s+([A-Za-z0-9_-]{1,32})\\s+(?:go|point|redirect|lead)"),
            Pattern.compile("(?i)(?:clicks?|stats|statistics|analytics|traffic|referrers?|visits)\\s+(?:does|for|on|of|to)\\s+([A-Za-z0-9_-]{1,32})"),
            Pattern.compile("(?i)(?:does|has|is)\\s+([A-Za-z0-9_-]{1,32})\\s+(?:have|get|got|getting|doing|performing)"),
            Pattern.compile("(?i)(?:resolve|expand|look\\s?up)\\s+([A-Za-z0-9_-]{1,32})"),
    };
    private static final Set<String> PRONOUNS = Set.of("it", "that", "this", "the", "link", "one", "my");

    private int idCounter = 0;

    @Override
    public String name() {
        return "offline-planner";
    }

    @Override
    public LlmResponse complete(String systemPrompt, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools) {
        int requestIndex = lastUserTextIndex(messages);
        String request = (String) messages.get(requestIndex).get("content");
        List<String> earlierCodes = codesInConversation(messages.subList(0, requestIndex));
        Intent intent = Intent.parse(request, earlierCodes.isEmpty() ? null : earlierCodes.get(earlierCodes.size() - 1));
        intent.earlierCodes = earlierCodes;

        List<Map<String, Object>> uses = new ArrayList<>();
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        collectToolActivity(messages.subList(requestIndex + 1, messages.size()), uses, results);

        LlmResponse response = uses.isEmpty() ? plan(intent) : followUp(intent, uses, results);
        long in = (systemPrompt.length() + Json.write(messages).length()) / 4;
        long out = Json.write(response.content()).length() / 4;
        return new LlmResponse(response.content(), response.stopReason(), in, out);
    }

    // ------------------------------------------------------------------ first step: decide what to do

    private LlmResponse plan(Intent in) {
        if (in.delete) {
            return text("I can't delete links - I only have tools to create short links and report on them. "
                    + "Deleting a link would need an administrator.");
        }
        if (!in.urls.isEmpty() && (in.shorten || (!in.stats && !in.analytics && !in.resolve))) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(textBlock(in.urls.size() == 1 ? "I'll create that short link." : "I'll create those short links."));
            for (String url : in.urls.subList(0, Math.min(3, in.urls.size()))) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("url", url);
                if (in.alias != null && in.urls.size() == 1) input.put("alias", in.alias);
                if (in.ttlSeconds != null) input.put("ttl_seconds", in.ttlSeconds);
                blocks.add(toolUse("shorten_url", input));
            }
            return new LlmResponse(blocks, "tool_use", 0, 0);
        }
        if ((in.stats || in.analytics || in.resolve) && in.code == null) {
            return text("Which short link do you mean? Give me its code or alias.");
        }
        if (in.analytics) {
            return new LlmResponse(List.of(toolUse("get_link_analytics", Map.of("code", in.code))), "tool_use", 0, 0);
        }
        if (in.stats || in.resolve) {
            return new LlmResponse(List.of(toolUse("get_link_stats", Map.of("code", in.code))), "tool_use", 0, 0);
        }
        return text("I can shorten URLs (optionally with a custom alias and an expiry), tell you where a short "
                + "link points and how many clicks it has, and show its traffic analytics. What would you like?");
    }

    // ------------------------------------------------------------------ later steps: chain or summarize

    private LlmResponse followUp(Intent in, List<Map<String, Object>> uses, Map<String, Map<String, Object>> results) {
        boolean statsCalled = uses.stream().anyMatch(u -> String.valueOf(u.get("name")).startsWith("get_link"));
        if ((in.stats || in.analytics) && !statsCalled) {
            for (Map<String, Object> use : uses) {
                Map<String, Object> r = results.get((String) use.get("id"));
                if ("shorten_url".equals(use.get("name")) && r != null && !Boolean.TRUE.equals(r.get("is_error"))) {
                    String code = (String) Json.parseObject((String) r.get("content")).get("shortCode");
                    String tool = in.analytics ? "get_link_analytics" : "get_link_stats";
                    return new LlmResponse(List.of(textBlock("Now I'll check that link."),
                            toolUse(tool, Map.of("code", code))), "tool_use", 0, 0);
                }
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> use : uses) {
            Map<String, Object> r = results.get((String) use.get("id"));
            if (r != null) lines.add(describe(use, r, in));
        }
        return text(String.join("\n", lines));
    }

    @SuppressWarnings("unchecked")
    private String describe(Map<String, Object> use, Map<String, Object> result, Intent in) {
        String tool = (String) use.get("name");
        Map<String, Object> input = (Map<String, Object>) use.get("input");
        Map<String, Object> data = Json.parseObject((String) result.get("content"));
        if (Boolean.TRUE.equals(result.get("is_error"))) {
            Map<String, Object> err = (Map<String, Object>) data.getOrDefault("error", Map.of());
            String code = String.valueOf(err.get("code"));
            String msg = String.valueOf(err.get("message"));
            return switch (code) {
                case "blocked_by_policy" -> "I didn't do that - it's blocked by policy: " + msg + ".";
                case "declined_by_human" -> "Okay, I did not create it (the reviewer declined).";
                case "alias_taken" -> "I couldn't create it: " + msg + ". Would '" + input.get("alias")
                        + "-2' work instead?";
                case "not_found" -> "There is no short link '" + input.get("code") + "'.";
                case "expired" -> "The link '" + input.get("code") + "' has expired.";
                case "rate_limited" -> "The service is rate-limiting requests right now; please try again shortly.";
                default -> "That didn't work: " + msg;
            };
        }
        return switch (tool) {
            case "shorten_url" -> (in.earlierCodes.contains(data.get("shortCode"))
                    ? "That URL was already shortened, so the service returned the existing link: "
                    : "Created ") + data.get("shortUrl") + " -> " + data.get("longUrl")
                    + (data.get("expiresAt") == null ? " (no expiry)." : " (expires " + data.get("expiresAt") + ").");
            case "get_link_stats" -> in.resolve && !in.stats
                    ? data.get("shortCode") + " points to " + data.get("longUrl") + "."
                    : data.get("shortCode") + " -> " + data.get("longUrl") + " has " + plural(data.get("clickCount"), "click") + ".";
            case "get_link_analytics" -> analyticsSentence(data);
            default -> tool + " returned " + Json.write(data);
        };
    }

    @SuppressWarnings("unchecked")
    private static String analyticsSentence(Map<String, Object> d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.get("shortCode")).append(": ").append(plural(d.get("totalClicks"), "click")).append(" total, ")
                .append(d.get("clicksLast24h")).append(" in the last 24h, ").append(d.get("clicksLast7d"))
                .append(" in the last 7 days.");
        List<Map<String, Object>> refs = (List<Map<String, Object>>) d.getOrDefault("topReferrers", List.of());
        if (!refs.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map<String, Object> r : refs) parts.add(r.get("referrer") + " (" + r.get("clicks") + ")");
            sb.append(" Top referrers: ").append(String.join(", ", parts)).append('.');
        }
        return sb.toString();
    }

    private static String plural(Object n, String word) {
        long v = n instanceof Number num ? num.longValue() : 0;
        return v + " " + word + (v == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------ conversation helpers

    private static int lastUserTextIndex(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if ("user".equals(m.get("role")) && m.get("content") instanceof String) return i;
        }
        throw new IllegalStateException("no user request in conversation");
    }

    @SuppressWarnings("unchecked")
    private static void collectToolActivity(List<Map<String, Object>> msgs, List<Map<String, Object>> uses,
                                            Map<String, Map<String, Object>> results) {
        for (Map<String, Object> m : msgs) {
            if (!(m.get("content") instanceof List)) continue;
            for (Map<String, Object> block : (List<Map<String, Object>>) m.get("content")) {
                if ("tool_use".equals(block.get("type"))) uses.add(block);
                if ("tool_result".equals(block.get("type"))) results.put((String) block.get("tool_use_id"), block);
            }
        }
    }

    /** Links discussed earlier, so "how many clicks does it have?" works in a follow-up turn. */
    @SuppressWarnings("unchecked")
    private static List<String> codesInConversation(List<Map<String, Object>> msgs) {
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> m : msgs) {
            if (!(m.get("content") instanceof List)) continue;
            for (Map<String, Object> block : (List<Map<String, Object>>) m.get("content")) {
                if ("tool_result".equals(block.get("type")) && !Boolean.TRUE.equals(block.get("is_error"))) {
                    Object c = Json.parseObject((String) block.get("content")).get("shortCode");
                    if (c != null) codes.add((String) c);
                }
            }
        }
        return codes;
    }

    private LlmResponse text(String s) {
        return new LlmResponse(List.of(textBlock(s)), "end_turn", 0, 0);
    }

    private static Map<String, Object> textBlock(String s) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "text");
        b.put("text", s);
        return b;
    }

    private Map<String, Object> toolUse(String name, Map<String, Object> input) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "tool_use");
        b.put("id", "toolu_offline_" + (++idCounter));
        b.put("name", name);
        b.put("input", input);
        return b;
    }

    /** What the user asked for, extracted with simple patterns. */
    private static final class Intent {
        final List<String> urls = new ArrayList<>();
        List<String> earlierCodes = List.of();
        String alias;
        Long ttlSeconds;
        String code;
        boolean shorten, stats, analytics, resolve, delete;

        static Intent parse(String text, String lastCode) {
            Intent in = new Intent();
            String lower = text.toLowerCase();
            Matcher m = URL.matcher(text);
            while (m.find()) in.urls.add(m.group(1).replaceAll("[.)]+$", ""));
            m = ALIAS.matcher(text);
            if (m.find()) in.alias = m.group(1);
            m = TTL.matcher(text);
            if (m.find()) {
                long n = Long.parseLong(m.group(1));
                String unit = m.group(2).toLowerCase();
                long mult = unit.startsWith("min") ? 60 : unit.startsWith("h") ? 3600 : unit.startsWith("d") ? 86400 : 604800;
                in.ttlSeconds = n * mult;
            }
            for (Pattern p : CODE_PATTERNS) {
                m = p.matcher(text);
                if (m.find()) {
                    in.code = PRONOUNS.contains(m.group(1).toLowerCase()) ? lastCode : m.group(1);
                    break;
                }
            }
            if (in.code == null && lower.matches(".*\\b(it|that one|this one)\\b.*")) in.code = lastCode;
            in.shorten = lower.matches(".*\\b(shorten|short link|shorter|create|make)\\b.*");
            in.analytics = lower.matches(".*\\b(analytics|referrers?|traffic|last 24|this week|per day|daily|performing|doing)\\b.*");
            in.stats = !in.analytics && lower.matches(".*\\b(clicks?|stats|statistics|how many|visits)\\b.*");
            in.resolve = lower.matches(".*\\b(where does|where do|resolve|expand|destination|points? to)\\b.*");
            in.delete = lower.matches(".*\\b(delete|remove|disable)\\b.*");
            return in;
        }
    }
}
