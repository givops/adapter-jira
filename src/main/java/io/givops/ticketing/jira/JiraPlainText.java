package io.givops.ticketing.jira;

import static io.givops.ticketing.jira.JiraPayload.invalidResponse;
import static io.givops.ticketing.jira.JiraTransport.limitExceeded;

import io.givops.ticketing.port.TicketRequestGate;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Text-only ADF projection: marks, URLs, media and extension attributes never become executable content. */
final class JiraPlainText {
    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "codeBlock", "blockquote", "listItem",
            "tableRow", "bulletList", "orderedList", "panel", "expand", "nestedExpand");
    private static final Set<String> CONTAINERS = Set.of("doc", "paragraph", "heading", "codeBlock", "blockquote", "listItem",
            "tableRow", "table", "tableCell", "tableHeader", "bulletList", "orderedList", "panel", "expand", "nestedExpand");
    private final StringBuilder text = new StringBuilder();
    private final TicketRequestGate gate;
    private int nodes;
    private JiraPlainText(TicketRequestGate gate) { this.gate = gate; }

    static String description(JsonNode node, TicketRequestGate gate) {
        if (node == null || node.isNull()) return "";
        if (node.isString()) return bounded(node.stringValue());
        JiraPayload.requireObject(node);
        if (!"doc".equals(JiraPayload.text(node, "type", true)) || !Integer.valueOf(1).equals(JiraPayload.offset(node, "version")))
            throw invalidResponse();
        var renderer = new JiraPlainText(gate);
        renderer.visit(node, 0);
        return bounded(renderer.text.toString().strip());
    }
    private void visit(JsonNode node, int depth) {
        if (depth > 64 || ++nodes > 100_000) throw limitExceeded();
        if (nodes % 64 == 0) gate.check();
        JiraPayload.requireObject(node);
        String type = JiraPayload.text(node, "type", true);
        switch (type) {
            case "text" -> append(JiraPayload.text(node, "text", true));
            case "hardBreak", "rule" -> append("\n");
            case "mention", "emoji", "status" -> {
                JsonNode attrs = node.get("attrs");
                if (attrs != null && attrs.isObject()) {
                    String value = JiraPayload.text(attrs, "text", false);
                    if (value == null && type.equals("emoji")) value = JiraPayload.text(attrs, "shortName", false);
                    if (value != null) append(value);
                }
            }
            default -> {
                // Only known text containers are traversed; media, cards and extension payloads are omitted.
                if (CONTAINERS.contains(type)) {
                    JsonNode children = node.get("content");
                    if (children != null) {
                        if (!children.isArray()) throw invalidResponse();
                        for (var child : children) visit(child, depth + 1);
                    }
                    if (BLOCKS.contains(type) && !text.isEmpty() && text.charAt(text.length() - 1) != '\n') append("\n");
                    if ((type.equals("tableCell") || type.equals("tableHeader")) && !text.isEmpty()) append("\t");
                }
            }
        }
    }
    private void append(String value) {
        if (value.length() > 1024 * 1024 - text.length()) throw limitExceeded();
        text.append(value);
    }
    private static String bounded(String value) {
        if (value.length() > 1024 * 1024 || value.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw limitExceeded();
        return value;
    }
}
