package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.19.9 SYSTEM_ADMIN 通報一覧の 1 行（{@code GET /api/v1/system-admin/ad-user-reports}）。
 *
 * <p>FE {@code types/adModeration.ts#AdUserReport} と項目名を一致させる。
 * メッセージ型/運用型の種別は {@code campaignId}（非 null なら messaging）/
 * {@code operationalCampaignId}（非 null なら operational）で判別する（種別バッジ用）。
 * {@code autoSuspendCandidate} は同一キャンペーンの未処理通報（NEW/REVIEWING）が閾値 3 件以上に
 * 達しているかを示す（FE のハイライト用）。</p>
 *
 * @param id                    通報 ID
 * @param campaignId            メッセージ型キャンペーン ID（運用型時 null）
 * @param operationalCampaignId 運用型キャンペーン ID（メッセージ型時 null）
 * @param userId                通報者 user_id（退会時 null）
 * @param reason                通報理由
 * @param detail                自由記述（null 可）
 * @param status                対応状態
 * @param autoSuspendCandidate  未処理通報が閾値以上か（自動停止候補）
 * @param reportedAt            通報時刻
 */
public record AdUserReportAdminResponse(
        UUID id,
        UUID campaignId,
        Long operationalCampaignId,
        Long userId,
        AdReportReasonCode reason,
        String detail,
        AdReportStatus status,
        boolean autoSuspendCandidate,
        LocalDateTime reportedAt
) {
}
