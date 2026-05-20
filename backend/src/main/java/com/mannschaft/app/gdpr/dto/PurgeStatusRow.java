package com.mannschaft.app.gdpr.dto;

import java.time.LocalDateTime;

/**
 * GDPR パージ状況 1 行分の DTO。
 *
 * <p>システム管理者向け GDPR purge 状況 API のレスポンス単位。
 * {@code isAlert = true} は PENDING かつ 30 分超過を表す（GDPR Art.17 監視指標）。</p>
 *
 * @param userId        削除対象ユーザーの ID
 * @param emailHash     退会時の email を SHA-256 でハッシュ化した値（PII なし）
 * @param domainName    ドメイン識別子（role / team / payment / chart / proxy / errorreport）
 * @param status        処理状態（PENDING / SUCCESS）
 * @param attemptedAt   PENDING レコード作成日時
 * @param completedAt   SUCCESS に更新された日時（PENDING 中は null）
 * @param isAlert       PENDING かつ 30 分超過の場合 true（GDPR 監視アラート対象）
 * @param retryCount    管理者による手動 retry の累計実行回数（Phase F 追加）
 * @param lastRetriedAt 管理者が最後に retry を実行した日時（Phase F 追加、未実行は null）
 */
public record PurgeStatusRow(
        Long userId,
        String emailHash,
        String domainName,
        String status,
        LocalDateTime attemptedAt,
        LocalDateTime completedAt,
        boolean isAlert,
        Integer retryCount,
        LocalDateTime lastRetriedAt
) {}
