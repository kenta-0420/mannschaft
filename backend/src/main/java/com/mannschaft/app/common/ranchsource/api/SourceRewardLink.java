package com.mannschaft.app.common.ranchsource.api;

/** 現在の源認可を通過した内部画面リンク。URLは源の実ルートから決定する。 */
public record SourceRewardLink(Kind kind, String id, String url) {
    public enum Kind { TIMELINE, SCHEDULE, BLOG, REFLECTION_ENTRY }
    public SourceRewardLink {
        if (kind == null || id == null || id.isBlank() || id.length() > 80
                || url == null || !url.startsWith("/") || url.startsWith("//")
                || url.length() > 1024 || url.indexOf('\\') >= 0
                || url.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("報酬源リンクが不正です");
        }
    }
}
