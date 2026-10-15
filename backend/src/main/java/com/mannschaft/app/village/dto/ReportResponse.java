package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageReportEntity;
import com.mannschaft.app.village.entity.enums.VillageReportStatus;
import com.mannschaft.app.village.entity.enums.VillageReportTargetType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村内通報レスポンス（F17.1 Phase 1 B7）。
 *
 * <p><strong>セキュリティ要件（設計書 §6.2）:</strong>
 * {@code reporter_user_id} は外部 API レスポンスに絶対に含めず、
 * {@code reporterDisplayName} を固定値 {@code "ANONYMOUS_VILLAGER"} でマスクする。
 * 被報告者が報復するのを防ぐためである。</p>
 *
 * @param id                   通報 ID（UUIDv7）
 * @param targetType           通報対象種別
 * @param targetRefId          対象 ID
 * @param reasonCode           通報理由コード
 * @param status               処理状態（PENDING / REVIEWING / RESOLVED / DISMISSED）
 * @param reporterDisplayName  固定値「ANONYMOUS_VILLAGER」
 * @param reportedAt           通報日時
 * @param handlerAction        処理担当が記録したアクション（解決済みの場合）
 * @param handledAt            処理日時
 */
public record ReportResponse(
        UUID id,
        VillageReportTargetType targetType,
        String targetRefId,
        String reasonCode,
        VillageReportStatus status,
        String reporterDisplayName,
        LocalDateTime reportedAt,
        String handlerAction,
        LocalDateTime handledAt
) {

    /** 通報者を一切露出させない固定値ラベル（設計書 §6.2 / §10 ANONYMOUS_VILLAGER）。 */
    public static final String ANONYMOUS_REPORTER = "ANONYMOUS_VILLAGER";

    /**
     * Entity からレスポンス DTO を生成する。
     * {@code reporter_user_id} はマスクされ {@code reporterDisplayName=ANONYMOUS_VILLAGER} 固定。
     */
    public static ReportResponse from(VillageReportEntity entity) {
        return new ReportResponse(
                entity.getId(),
                entity.getTargetType(),
                entity.getTargetRefId(),
                entity.getReasonCode(),
                entity.getStatus(),
                ANONYMOUS_REPORTER,
                entity.getCreatedAt(),
                entity.getHandlerAction(),
                entity.getHandledAt());
    }
}
