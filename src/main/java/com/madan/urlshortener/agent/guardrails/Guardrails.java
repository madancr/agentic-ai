package com.madan.urlshortener.agent.guardrails;

import com.madan.urlshortener.agent.tools.Tool;
import com.madan.urlshortener.agent.tools.ToolRegistry;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Policy checks applied to EVERY tool call the model asks for, before anything executes.
 * The model proposes; this class decides. Checks, in order:
 *
 * <ol>
 *   <li><b>Allow-list</b> - only registered tools can run (a hallucinated "delete_link" is refused).</li>
 *   <li><b>Budget</b> - at most {@code maxToolCallsPerRequest} tool calls per user request (runaway loops).</li>
 *   <li><b>Schema</b> - arguments must match the tool's JSON schema (model output is untrusted input).</li>
 *   <li><b>Destination policy</b> - no short links to internal/private hosts or deny-listed domains
 *       (stops the agent from publishing links to internal systems).</li>
 *   <li><b>Human confirmation</b> - high-impact calls (a public custom alias) need a "yes".</li>
 * </ol>
 */
public class Guardrails {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private final ToolRegistry registry;
    private final int maxToolCallsPerRequest;
    private final Set<String> deniedDomains;

    public Guardrails(ToolRegistry registry, int maxToolCallsPerRequest, Set<String> deniedDomains) {
        this.registry = registry;
        this.maxToolCallsPerRequest = maxToolCallsPerRequest;
        this.deniedDomains = deniedDomains;
    }

    public GuardrailDecision check(String toolName, Map<String, Object> input, int callsSoFarThisRequest) {
        Tool tool = registry.get(toolName);
        if (tool == null) {
            return GuardrailDecision.deny("allow_list", "tool '" + toolName + "' does not exist or is not permitted");
        }
        if (callsSoFarThisRequest >= maxToolCallsPerRequest) {
            return GuardrailDecision.deny("budget", "tool-call budget of " + maxToolCallsPerRequest + " per request used up");
        }
        String schemaError = SchemaValidator.validate(tool.inputSchema(), input);
        if (schemaError != null) {
            return GuardrailDecision.deny("schema", schemaError);
        }
        if (input.get("url") instanceof String url) {
            String destinationError = checkDestination(url);
            if (destinationError != null) {
                return GuardrailDecision.deny("destination_policy", destinationError);
            }
        }
        String confirmation = tool.confirmationReason(input);
        if (confirmation != null) {
            return GuardrailDecision.confirm(confirmation);
        }
        return GuardrailDecision.allow();
    }

    /** Returns an error message if the URL points somewhere the agent must not publish, else null. */
    String checkDestination(String url) {
        String host;
        try {
            host = URI.create(url.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return null; // malformed URLs are rejected by the service's own validation, with a clearer message
        }
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".corp") || host.equals("0.0.0.0")
                || host.startsWith("[")) {
            return "links to internal hosts (" + host + ") are not allowed";
        }
        var m = IPV4.matcher(host);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            boolean privateRange = a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 169 && b == 254);
            if (privateRange) {
                return "links to private network addresses (" + host + ") are not allowed";
            }
        }
        for (String denied : deniedDomains) {
            if (host.equals(denied) || host.endsWith("." + denied)) {
                return "domain " + denied + " is on the deny list";
            }
        }
        return null;
    }

    /** Minimal JSON-schema checks: required fields, no unknown fields, types, pattern, ranges, length. */
    static final class SchemaValidator {

        @SuppressWarnings("unchecked")
        static String validate(Map<String, Object> schema, Map<String, Object> input) {
            if (input == null) {
                return "arguments must be a JSON object";
            }
            Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (Object req : (List<Object>) schema.getOrDefault("required", List.of())) {
                if (input.get(req) == null) return "missing required argument '" + req + "'";
            }
            for (Map.Entry<String, Object> arg : input.entrySet()) {
                Map<String, Object> prop = (Map<String, Object>) props.get(arg.getKey());
                if (prop == null) {
                    if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                        return "unknown argument '" + arg.getKey() + "'";
                    }
                    continue;
                }
                String err = checkValue(arg.getKey(), arg.getValue(), prop);
                if (err != null) return err;
            }
            return null;
        }

        private static String checkValue(String name, Object value, Map<String, Object> prop) {
            if (value == null) return null;
            String type = (String) prop.get("type");
            if ("string".equals(type)) {
                if (!(value instanceof String s)) return "'" + name + "' must be a string";
                if (prop.get("maxLength") instanceof Number max && s.length() > max.intValue()) {
                    return "'" + name + "' is too long";
                }
                if (prop.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).matches()) {
                    return "'" + name + "' does not match " + p;
                }
            } else if ("integer".equals(type)) {
                if (!(value instanceof Long || value instanceof Integer)) return "'" + name + "' must be an integer";
                long v = ((Number) value).longValue();
                if (prop.get("minimum") instanceof Number min && v < min.longValue()) {
                    return "'" + name + "' must be >= " + min;
                }
                if (prop.get("maximum") instanceof Number max && v > max.longValue()) {
                    return "'" + name + "' must be <= " + max;
                }
            }
            return null;
        }
    }
}
