package com.mannschaft.app.mail.outbox;

import java.util.Map;

/**
 * F09.18 メール配信 enqueue リクエスト DTO。
 *
 * <p>呼び出し側ドメインの {@code @TransactionalEventListener(AFTER_COMMIT)}
 * から構築する。設計書 §6.1 の {@code EmailOutboxRequest} に対応。</p>
 *
 * @param templateKind   必須: 例 "VERIFICATION" (§11 マトリクスの 14 種から選択)
 * @param locale         必須: ja/en/zh/ko/es/de
 * @param toAddress      必須: RFC 5322 準拠の宛先メールアドレス
 * @param payloadVars    必須: HTML 禁止、構造化変数のみ (Map<String, String>)
 * @param sourceDomain   必須: 8 ドメインのいずれか
 * @param sourceEventId  任意: 業務側の冪等キー (token.id, batch_run_id 等)
 * @param idempotencyKey 任意: 省略時は sha256(user_id:template_kind:nonce)[:32] 自動生成
 * @param userId         任意: 受信者 user_id (認証前のメールでは null)
 * @param organizationId 任意: 認証メールでは null
 */
public record EmailOutboxRequest(
        String templateKind,
        String locale,
        String toAddress,
        Map<String, String> payloadVars,
        String sourceDomain,
        String sourceEventId,
        String idempotencyKey,
        Long userId,
        Long organizationId
) {
}
