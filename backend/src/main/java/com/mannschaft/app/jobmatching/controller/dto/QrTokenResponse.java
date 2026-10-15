package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;

import java.time.Instant;

/**
 * QR トークン発行／取得レスポンス。F13.1 Phase 13.1.2。
 *
 * <p>{@code POST /api/v1/contracts/{id}/qr-tokens} および
 * {@code GET /api/v1/contracts/{id}/qr-tokens/current} の正常系で返す。</p>
 *
 * <p>Requester のデバイス上で {@code token} を QR コード化して Worker にスキャンさせる。
 * QR 読取失敗時は {@code shortCode} を口頭または画面表示で伝達し、Worker が手動入力する。</p>
 *
 * @param token     QR 画像化対象の JWT 文字列。{@code GET /current} で既存トークンを参照する
 *                  ケースでは JWT 自体は DB に保存されていないため {@code null}（クライアントは
 *                  shortCode とメタ情報のみで運用するか、再発行する）
 * @param shortCode 手動入力フォールバック用の短コード（紛らわしい文字を除外した英数 6 桁）
 * @param type      IN / OUT
 * @param issuedAt  発行時刻（UTC, ミリ秒精度）
 * @param expiresAt 失効時刻（UTC, ミリ秒精度）
 * @param kid       発行に用いた署名鍵 ID
 */
public record QrTokenResponse(
        String token,
        String shortCode,
        JobCheckInType type,
        Instant issuedAt,
        Instant expiresAt,
        String kid
) {
}
