package io.givops.ticketing.jira;

import io.givops.adapters.i18n.MessageCatalog;
import java.util.Map;

/** This artifact owns its messages; no host catalog or classpath-wide lookup is used. */
final class JiraMessages {
    private static final MessageCatalog ENGLISH = MessageCatalog.load(JiraMessages.class,
            "/io/givops/adapters/jira/i18n/en.yml");

    private JiraMessages() { }

    static String format(String key) { return ENGLISH.format(key, Map.of()); }

    static Map<String, String> patterns() { return ENGLISH.patterns(); }
}
