package com.madan.urlshortener.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The allow-list of tools. If a tool is not registered here, the agent cannot call it -
 * no matter what the model asks for.
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** Tool definitions in the format the Claude Messages API expects. */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", t.name());
            d.put("description", t.description());
            d.put("input_schema", t.inputSchema());
            defs.add(d);
        }
        return defs;
    }

    /** The three tools that map onto the service's existing endpoints. */
    public static ToolRegistry standard(ShortenerApiClient api) {
        return new ToolRegistry()
                .register(new ShortenUrlTool(api))
                .register(new GetLinkStatsTool(api))
                .register(new GetLinkAnalyticsTool(api));
    }
}
