package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * QR チェックイン／アウト記録リクエスト。F13.1 Phase 13.1.2。
 *
 * <p>Worker が {@code POST /api/v1/jobs/check-ins} で送信する。
 * {@code token}（JWT）と {@code shortCode} はいずれか片方必須（両方指定時は {@code token} を優先）。
 * Service 層（{@link com.mannschaft.app.jobmatching.service.JobCheckInService}）でも再チェックするが、
 * Controller 側の JSR-303 バリデーションでは「両方 null」ケースのみをカスタムでは検証せず Service 層に委ねる。</p>
 *
 * <p>緯度経度の範囲チェックは {@code @DecimalMin/@DecimalMax} で軽く防御する。
 * GPS 精度と User-Agent は長さを超過しない範囲で受け取り、null のままでも構わない。</p>
 *
 * @param contractId         対象契約 ID（必須）
 * @param token              スキャンした JWT 文字列（token / shortCode のいずれか必須）
 * @param shortCode          手動入力された短コード（同上）
 * @param type               IN / OUT（必須）
 * @param scannedAt          クライアントでスキャンした時刻（必須、ミリ秒精度 UTC）
 * @param offlineSubmitted   オフライン送信（PWA の IndexedDB 経由リプレイ）フラグ
 * @param manualCodeFallback 手動入力フォールバックで送信されたか
 * @param geoLat             端末緯度（-90.0〜90.0、未同意時 null）
 * @param geoLng             端末経度（-180.0〜180.0、未同意時 null）
 * @param geoAccuracy        GPS 精度（メートル、非負、null 可）
 * @param clientUserAgent    クライアント User-Agent（ログ・監査用、最大 512 文字、null 可）
 */
public record RecordCheckInRequest(
        @NotNull Long contractId,
        @Size(max = 4096) String token,
        @Size(max = 16) String shortCode,
        @NotNull JobCheckInType type,
        @NotNull Instant scannedAt,
        boolean offlineSubmitted,
        boolean manualCodeFallback,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double geoLat,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double geoLng,
        @DecimalMin("0.0") Double geoAccuracy,
        @Size(max = 512) String clientUserAgent
) {
}
