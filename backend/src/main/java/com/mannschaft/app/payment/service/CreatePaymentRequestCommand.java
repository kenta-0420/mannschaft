package com.mannschaft.app.payment.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F08.9 P7: 協会→加盟チーム請求の発行コマンド（{@link PaymentRequestService#create}）。
 *
 * <p>発行者（協会 ADMIN）の権原・テナント（orgId）・操作者は {@code create} の引数で渡し、本コマンドは
 * 請求の中身（請求先チーム・額面・期限・税区分・再請求の旧行参照）を運ぶ。着金先 Connect 口座は
 * サービスが発行者（協会）の scope から解決して焼き付ける（呼び出し側は渡さない）。</p>
 *
 * <ul>
 *   <li>{@code payerTeamId} — 請求先チーム ID（{@code payment_requests.payer_scope_id}・TEAM）。</li>
 *   <li>{@code title} — 請求タイトル（必須・最大120文字）。</li>
 *   <li>{@code description} — 請求の説明（任意・最大1000文字）。</li>
 *   <li>{@code faceAmount} — 額面（円整数・最小通貨単位・正値）。</li>
 *   <li>{@code currency} — 通貨（{@code null}＝JPY）。</li>
 *   <li>{@code taxCategory} — 税区分（{@code null}＝税なし扱い・NoOpTaxPolicy）。</li>
 *   <li>{@code dueDate} — 支払期限（必須）。</li>
 *   <li>{@code supersededRequestId} — 再請求時に supersede する旧 CANCELLED 請求の ID（{@code null}＝新規請求）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §7。</p>
 */
public record CreatePaymentRequestCommand(
        Long payerTeamId,
        String title,
        String description,
        long faceAmount,
        String currency,
        String taxCategory,
        LocalDate dueDate,
        UUID supersededRequestId) {
}
