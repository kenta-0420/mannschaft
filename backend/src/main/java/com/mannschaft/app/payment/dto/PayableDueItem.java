package com.mannschaft.app.payment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * F08.9 P2: 後見まとめ払い — 払える未払い会費 1 明細（設計書 02_api_design §1.2）。
 *
 * <p>{@code GET /api/v1/me/payable-dues} の {@code items[]} 要素。払い手（保護者・本人）視点で、
 * 本人＋後見下の子それぞれの未払い会費を 1 行に展開する。権原（SELF/GUARDIAN/...）が成立する明細のみ含む
 * （無権原の受益者は IDOR 防止のため一切含めない）。camelCase 1:1。</p>
 *
 * @param beneficiaryUserId      受益者（会費の対象者）ユーザーID
 * @param beneficiaryDisplayName 受益者の表示名（UI 表示用）
 * @param scopeType              会費スコープ種別（TEAM / ORGANIZATION）
 * @param scopeId                会費スコープ ID（teamId または organizationId）
 * @param scopeName              会費スコープ名（チーム名 / 組織名・解決できなければ null）
 * @param paymentItemId          会費項目 ID
 * @param itemName               会費項目名
 * @param faceAmount             額面（payment_item.amount）
 * @param payerSurcharge         払い手手数料（現状 0・将来 fee policy 連動）
 * @param totalCharge            合計請求額（faceAmount + payerSurcharge）
 * @param dueDate                支払期限（解決できなければ null）
 * @param kind                   会費種別（ONE_TIME / RECURRING / TERM）
 * @param authorizationVia       権原経路（SELF / GUARDIAN / GUARDIAN_PROXY / PROXY_GRANT / ADMIN_MANUAL）
 * @param alreadyPaid            既に有効な支払い済みか（通常 false・整合性確認用に含める）
 * @param paidByUserId           支払い済みの場合の払い手ユーザーID（null 可）
 * @param paidByDisplayName      支払い済みの場合の払い手表示名（null 可）
 * @param paidAt                 支払い済みの場合の支払い日時（null 可）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayableDueItem(
        Long beneficiaryUserId,
        String beneficiaryDisplayName,
        String scopeType,
        Long scopeId,
        String scopeName,
        Long paymentItemId,
        String itemName,
        int faceAmount,
        int payerSurcharge,
        int totalCharge,
        LocalDate dueDate,
        String kind,
        String authorizationVia,
        boolean alreadyPaid,
        Long paidByUserId,
        String paidByDisplayName,
        Instant paidAt
) {
}
