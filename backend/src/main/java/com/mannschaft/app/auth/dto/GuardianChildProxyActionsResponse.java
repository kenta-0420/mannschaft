package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F08.9 件2／件3 保護者による子データ閲覧（代理入力履歴）レスポンス。
 *
 * <p>{@code GET /api/v1/me/guardianship/children/{childUserId}/proxy-actions} の返却。
 * {@code proxy_input_records}（F14.1 代理入力）から <b>subject=子</b> のレコードのみを新しい順で返す。
 * 「子の代わりに誰が・どの機能で・何を・どの入力元で行ったか」を保護者へ透明化する代理バッジ用データ。
 * subjectUserId は常に子（= childUserId）ゆえ冗長返却しない。</p>
 *
 * @param items 代理入力項目（作成日時降順）
 */
public record GuardianChildProxyActionsResponse(
        List<ProxyActionItem> items) {

    /**
     * 代理入力 1 件。
     *
     * @param id               代理入力記録 ID
     * @param proxyUserId      代理者（子の代わりに操作した人）のユーザー ID
     * @param featureScope     操作対象機能スコープ（例: SCHEDULE_ATTENDANCE / PAYMENT）
     * @param targetEntityType 操作対象エンティティ種別（例: SCHEDULE_ATTENDANCE）
     * @param targetEntityId   操作対象レコード ID
     * @param inputSource      入力元（PAPER_FORM / PHONE_INTERVIEW / IN_PERSON / GUARDIANSHIP_SWITCH）
     * @param createdAt        作成日時
     */
    public record ProxyActionItem(
            Long id,
            Long proxyUserId,
            String featureScope,
            String targetEntityType,
            Long targetEntityId,
            String inputSource,
            LocalDateTime createdAt) {
    }
}
