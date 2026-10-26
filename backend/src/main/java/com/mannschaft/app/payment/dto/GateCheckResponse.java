package com.mannschaft.app.payment.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * F08.9 P4: ペイウォール判定レスポンス（設計書 F08.9 02 §6 / 03_security §4）。
 *
 * <p>閲覧者本人（受益者キー＝viewer 自身）の支払い状態のみで判定した結果を返す。
 * 他人の支払いで解錠されることはない（受益者キー判定・03_security §4）。</p>
 *
 * <ul>
 *   <li>{@code accessible} — コンテンツに紐づく全ゲートの payment_item を viewer が支払い済みなら true。
 *       1つでも未充足、または判定不能（fail-safe）なら false。</li>
 *   <li>{@code titleHidden} — いずれかのゲートが {@code is_title_hidden=true} の場合 true（存在ごと秘匿・404 相当）。</li>
 *   <li>{@code requiredItems} — 未払い者へ購入導線を出すための要求項目一覧。
 *       ただし {@code titleHidden=true} のときは存在秘匿のため空配列にする（名称・金額も露出させない）。</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class GateCheckResponse {

    private final boolean accessible;
    private final boolean titleHidden;
    private final List<RequiredItem> requiredItems;

    /**
     * ペイウォール解錠に必要な支払い項目（購入導線用）。
     *
     * <p>{@code satisfied} は viewer 自身がこの項目を支払い済みかどうか。</p>
     */
    @Getter
    @RequiredArgsConstructor
    public static class RequiredItem {
        private final Long paymentItemId;
        private final String name;
        private final BigDecimal faceAmount;
        private final boolean satisfied;
    }
}
