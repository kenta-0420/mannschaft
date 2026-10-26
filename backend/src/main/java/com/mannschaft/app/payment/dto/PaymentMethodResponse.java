package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * F08.9 P5 第二波: 支払い方法 confirm 結果レスポンス（設計書 02 §4.1）。
 *
 * <p>PCI 禁則を避け、保存済みの既定 PaymentMethod ID（{@code pm_xxx}・参照のみ）と保存状態を返す。
 * カード番号等の機微情報は一切返さない（03 §1）。</p>
 */
@Getter
@Builder
public class PaymentMethodResponse {

    /** 既定に設定した Stripe PaymentMethod ID（{@code pm_xxx}）。 */
    private final String defaultPaymentMethod;

    /** 保存済みフラグ（{@code default_payment_method} が非 null かどうか）。 */
    private final boolean saved;
}
