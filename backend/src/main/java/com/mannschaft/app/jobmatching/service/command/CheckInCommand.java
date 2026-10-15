package com.mannschaft.app.jobmatching.service.command;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;

import java.time.Instant;

/**
 * QR チェックイン／アウト記録コマンド（Service 層入力 DTO）。
 *
 * <p>F13.1 Phase 13.1.2（設計書 §2.3.1 / §5.4）。Controller 層の Request DTO から
 * 組み立てられ、{@link com.mannschaft.app.jobmatching.service.JobCheckInService#recordCheckIn(CheckInCommand)}
 * に渡される。</p>
 *
 * <p>トークンは {@code token}（JWT）または {@code shortCode}（手動入力フォールバック）の
 * いずれか必須。両方指定された場合は {@code token} を優先する。</p>
 *
 * @param contractId          対象契約 ID（認可・ログ用、token から導出も可能だが明示的に受ける）
 * @param workerUserId        操作 Worker のユーザー ID（認証から）
 * @param token               スキャンした JWT 文字列（手動入力の場合 null）
 * @param shortCode           手動入力された 6 桁短コード（JWT 経由の場合 null）
 * @param type                IN / OUT
 * @param scannedAt           クライアントでスキャンした時刻（ミリ秒精度、UTC）
 * @param offlineSubmitted    オフライン送信フラグ（true なら scannedAt を基準に TTL 判定）
 * @param manualCodeFallback  手動入力フォールバックで送信されたか
 * @param geoLat              端末緯度（同意されなかった／取得失敗時は null）
 * @param geoLng              端末経度（同）
 * @param geoAccuracy         GPS 精度（メートル、null 可）
 * @param clientUserAgent     クライアント User-Agent（ログ・監査用、nullable）
 */
public record CheckInCommand(
        Long contractId,
        Long workerUserId,
        String token,
        String shortCode,
        JobCheckInType type,
        Instant scannedAt,
        boolean offlineSubmitted,
        boolean manualCodeFallback,
        Double geoLat,
        Double geoLng,
        Double geoAccuracy,
        String clientUserAgent
) {
}
