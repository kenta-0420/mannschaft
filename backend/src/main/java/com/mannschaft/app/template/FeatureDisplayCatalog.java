package com.mannschaft.app.template;

import java.util.List;
import java.util.stream.Stream;

/** F01.3.1の安定機能キー。モジュール有効化や業務APIの認可とは独立している。 */
public final class FeatureDisplayCatalog {
    private static final List<String> COMMON = List.of("announcements", "chat", "bulletin", "timeline", "blog",
            "schedule", "annual-plan", "market", "member-profiles", "circulation", "safety", "todos", "workflows",
            "forms", "projects", "incidents", "equipment", "parking", "files", "guide", "billing", "surveys",
            "tournaments", "info", "members", "activities", "gallery");
    private static final List<String> TEAM = Stream.concat(COMMON.stream(), Stream.of("shifts", "duties", "matches",
            "match-analytics", "skills", "repair-plan", "analytics", "league-transfers", "reservations", "nav", "member-info")).toList();
    private static final List<String> ORGANIZATION = Stream.concat(COMMON.stream(), Stream.of("events", "timetable",
            "voting", "committees", "facilities", "residents", "kb", "queue", "timeline-digest", "translations",
            "payments", "member-teams", "children")).toList();

    private FeatureDisplayCatalog() { }

    public static List<String> keys(FeatureDisplayScope scope) {
        return scope == FeatureDisplayScope.TEAM ? TEAM : ORGANIZATION;
    }
}
