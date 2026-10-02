package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.entity.AdUserReport;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.19.9 通報作成レスポンス（{@code 201: { "data": { "id", "status", "createdAt" } }}）。
 *
 * @param id        通報 ID（UUIDv7）
 * @param status    対応状態（作成直後は NEW）
 * @param createdAt 作成時刻
 */
public record AdReportCreatedResponse(
        UUID id,
        AdReportStatus status,
        LocalDateTime createdAt
) {
    public static AdReportCreatedResponse from(AdUserReport report) {
        return new AdReportCreatedResponse(report.getId(), report.getStatus(), report.getCreatedAt());
    }
}
