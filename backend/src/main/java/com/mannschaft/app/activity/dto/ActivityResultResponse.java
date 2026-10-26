package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 活動記録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
@Builder
public class ActivityResultResponse {

    private final Long id;
    private final TemplateInfo template;
    private final String title;
    private final LocalDate activityDate;
    private final LocalTime activityTimeStart;
    private final LocalTime activityTimeEnd;
    private final String description;
    private final Map<String, Object> fieldValues;
    private final String visibility;
    private final List<AttachmentInfo> attachments;
    private final List<ParticipantInfo> participants;
    private final Long scheduleId;
    private final CreatedByInfo createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * テンプレート概要。
     */
    @Getter
    @RequiredArgsConstructor
    public static class TemplateInfo {
        private final Long id;
        private final String name;
        private final String icon;
        private final String color;
    }

    /**
     * 添付ファイル情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AttachmentInfo {
        private final Long fileId;
        private final String fileName;
        private final String thumbnailUrl;
        private final String url;
    }

    /**
     * 参加者情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ParticipantInfo {
        private final Long userId;
        private final String displayName;
        private final String memberNumber;
        private final String roleLabel;
    }

    /**
     * 作成者情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class CreatedByInfo {
        private final Long id;
        private final String displayName;
    }
}
