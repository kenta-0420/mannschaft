package com.mannschaft.app.dashboard.dto;

import java.time.LocalDateTime;

/**
 * ダッシュボード向けプラットフォームお知らせレスポンスDTO（WidgetPlatformAnnouncements 用）。
 */
public record DashboardAnnouncementResponse(
        Long id,
        String title,
        String content,
        String severity,
        Boolean isPinned,
        LocalDateTime publishedAt
) {}
