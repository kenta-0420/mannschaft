package com.mannschaft.app.notification.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * メンション一覧レスポンスDTO。
 * フロントの {@code MentionList.vue} / {@code NotificationBell.vue} の Mention 型と整合する。
 */
@Getter
@Builder
public class MentionResponse {

    private final Long id;
    private final MentionedBy mentionedBy;
    private final String contentType;
    private final Long contentId;
    private final String contentTitle;
    private final String contentSnippet;
    private final String url;
    private final Boolean isRead;
    private final LocalDateTime createdAt;

    @Getter
    @Builder
    public static class MentionedBy {
        private final Long id;
        private final String displayName;
        private final String avatarUrl;
    }
}
