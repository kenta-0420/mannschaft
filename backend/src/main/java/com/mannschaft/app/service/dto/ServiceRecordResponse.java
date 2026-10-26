package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * サービス記録レスポンス。
 */
@Getter
@Builder
public class ServiceRecordResponse {

    private Long id;
    private Long teamId;
    private String teamName;
    private Long memberUserId;
    private Long staffUserId;
    private LocalDate serviceDate;
    private String title;
    private String note;
    private Integer durationMinutes;
    private String status;
    private List<CustomFieldValueResponse> customFields;
    private List<AttachmentResponse> attachments;
    private Long duplicatedFrom;
    private String myReaction;
    private Map<String, Integer> reactionSummary;
    private Boolean isReactionEnabled;
    private LocalDateTime createdAt;
}
