package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.PricingModel;

import java.math.BigDecimal;

/**
 * 広告料金カード公開レスポンス（広告主向け）。
 *
 * <p>F09.19.1b: 運用型キャンペーン作成 POST は {@code rateCardId}（{@code ad_rate_cards.id}）を必須とするため、
 * 広告主が正規経路で選択トークンとしての id を取得できるよう {@code id} を公開する。id は秘密値ではなく
 * （作成 POST 側で pricingModel 一致・effective 期間内を再検証する選択トークン）、公開しても IDOR にならない。
 * 単価・最低日予算などの機微でない料金情報のみを含み、作成者・監査情報は含めない。</p>
 */
public record PublicRateCardResponse(
        /** 料金カード id（作成 POST の rateCardId 選択トークン）。 */
        Long id,
        String targetPrefecture,
        String targetTemplate,
        PricingModel pricingModel,
        BigDecimal unitPrice,
        BigDecimal minDailyBudget,
        String label
) {
}
