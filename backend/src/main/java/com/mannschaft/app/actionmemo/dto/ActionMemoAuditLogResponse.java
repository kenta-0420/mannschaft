package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 行動メモ監査ログレスポンス DTO（Phase 5-1: 監査ログ可視化 UI 用）。
 *
 * <p>フロントエンドの折りたたみ UI で表示するため、
 * {@code AuditLogResponse} の全フィールドではなくメモ固有の情報のみを返す。</p>
 */
@Getter
@Builder
public class ActionMemoAuditLogResponse {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Long id;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("actor_id")
    private Long actorId;

    @JsonProperty("created_at")
    private String createdAt;

    private String metadata;

    /**
     * {@code AuditLogResponse} から変換するファクトリメソッド。
     */
    public static ActionMemoAuditLogResponse from(com.mannschaft.app.auth.dto.AuditLogResponse src) {
        return ActionMemoAuditLogResponse.builder()
                .id(src.getId())
                .eventType(src.getEventType())
                .actorId(src.getUserId())
                .createdAt(src.getCreatedAt() != null
                        ? src.getCreatedAt().format(FORMATTER)
                        : null)
                .metadata(src.getMetadata())
                .build();
    }
}
