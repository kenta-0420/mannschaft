package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Billing Center PR6b-1 残務③: 「いま販売中の band」を <b>現行 revision（＝最新の ACTIVE な
 * price_version）だけ</b>から解決する読み方の唯一の実装。
 *
 * <p><b>由来</b>: 元々 {@link BillingPlanChangePreviewService} に private メソッドとして
 * 存在していた band 解決ロジックをここへ抽出した（見積り確定の唯一の正）。FE の
 * {@code BillingManagePanel.vue} が「変更先候補」をカタログの {@code baseMonthlyPriceJpy}
 * （販売価格の正本ではない）で推測していた問題を修正するため、BE 側で
 * <b>見積りが実際に使っている band 解決と同じ読み方</b>を候補算出にも使い回す
 * （新しい流儀を作らない）。呼び出し元: {@link BillingPlanChangePreviewService}（見積り確定）、
 * {@code BillingEntitlementQueryService}（変更先候補の表示投影）。</p>
 */
@Component
@RequiredArgsConstructor
public class BillingCurrentBandResolver {

    private final BillingPriceVersionRepository priceVersionRepository;
    private final BillingPriceBandVersionRepository bandRepository;

    /**
     * 「いま販売中の band」を現行 revision 配下・指定人数レンジ・ACTIVE から解決する。
     *
     * <p><b>なぜ band を直接横断検索しないか</b>: 同じ商品・スコープに ACTIVE な band が複数世代
     * 残っていると、band を直接引く検索は bandNo が同値のとき順序が定まらず、<b>どの世代の値段で
     * 請求したのか説明できないまま金額を確定してしまう</b>。カタログの正は「現行 revision は1つ、
     * その配下の band が販売価格」であり、ここでもそれに揃える。</p>
     *
     * <p><b>古い世代へフォールバックしない</b>のも意図的である。現行 revision の band が
     * 削除・RETIRED・人数レンジ外で使えないなら、それは「いま売っていない」のであって、
     * 旧世代の値段で売ってよい理由にはならない。</p>
     *
     * @param productKind PLAN または ADDON
     * @param productKey  {@code product_key}
     * @param scopeKind   対象スコープ種別
     * @param memberCount 現在の人数（band のレンジ判定に使う）
     * @param now         判定時点
     * @return 現行 revision 配下で、いまの人数に当たる band。解決できなければ empty
     */
    @Transactional(readOnly = true)
    public Optional<BillingPriceBandVersionEntity> resolveCurrentBand(
            BillingProductKind productKind, String productKey, EntitlementScopeKind scopeKind,
            int memberCount, Instant now) {
        Optional<BillingPriceVersionEntity> revision = priceVersionRepository.findEffectiveCandidates(
                        productKind, productKey, scopeKind,
                        List.of(BillingPriceVersionStatus.ACTIVE), now)
                .stream().findFirst();
        if (revision.isEmpty()) {
            return Optional.empty();
        }

        return bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.get().getId())
                .stream()
                .filter(band -> band.getStatus() == BillingPriceVersionStatus.ACTIVE)
                .filter(band -> isEffectiveAt(band, now))
                .filter(band -> coversMemberCount(band, memberCount))
                .findFirst();
    }

    /**
     * 契約が現在課金されている band を解決する（AC-19 と同じ読み方）。
     * {@code price_band_version_id} が保存済みならそれを直接引き、NULL の既存契約は
     * {@link #resolveCurrentBand} へフォールバックする。
     */
    @Transactional(readOnly = true)
    public Optional<BillingPriceBandVersionEntity> resolveContractBand(
            BillingContractEntity contract, int memberCount, Instant now) {
        if (contract.getPriceBandVersionId() != null) {
            return bandRepository.findByIdAndDeletedAtIsNull(contract.getPriceBandVersionId());
        }
        return resolveCurrentBand(
                BillingProductKind.PLAN, contract.getPlanKey(), contract.getScopeKind(), memberCount, now);
    }

    private static boolean isEffectiveAt(BillingPriceBandVersionEntity band, Instant at) {
        return band.getEffectiveFrom() != null && !band.getEffectiveFrom().isAfter(at)
                && (band.getEffectiveUntil() == null || at.isBefore(band.getEffectiveUntil()));
    }

    private static boolean coversMemberCount(BillingPriceBandVersionEntity band, int memberCount) {
        return band.getMinMembers() != null && band.getMinMembers() <= memberCount
                && (band.getMaxMembers() == null || memberCount <= band.getMaxMembers());
    }
}
