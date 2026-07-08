package com.mannschaft.app.advertising.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * F09.19.2 割当ロジックの純粋関数部（正本 §7.2）。
 *
 * <p>DB / Valkey / Spring に依存しない決定的ロジックのみを扱う。ドメイン UT で
 * 順位付け（消化率昇順 → campaign.id 昇順）・ラウンドロビン・count=2 重複回避規則を
 * 単体検証できるよう切り出す。</p>
 *
 * <p><b>試練（red 先行）</b>: 本クラスは骨格のみ。全メソッドは {@link UnsupportedOperationException}
 * を投げる。出陣（実装）で正本 §7.2 のロジックを充填して green 化する。</p>
 */
public final class SpotlightAllocationSelector {

    /**
     * 割当候補（source 横断の共通表現）。
     *
     * @param source              "HOUSE" | "AFFILIATE"
     * @param creativeId          ads.id（HOUSE 運用型／予約。AFFILIATE は null）
     * @param campaignId          運用型 ad_campaigns.id（運用型のみ。予約／AFFILIATE は null）
     * @param advertiserAccountId 広告主アカウント id（HOUSE のみ。AFFILIATE は null）
     * @param spendRatio          本日消化率（運用型のみ。予約／AFFILIATE は null）
     * @param provider            "AMAZON" | "RAKUTEN"（AFFILIATE のみ）
     * @param reserved            F09.17 予約バナーなら true（最優先）
     */
    public record Candidate(
            String source,
            Long creativeId,
            Long campaignId,
            Long advertiserAccountId,
            BigDecimal spendRatio,
            String provider,
            boolean reserved) {
    }

    /**
     * 運用型候補を順位付けする（正本 §7.2 STEP 2 順位付け）。
     *
     * <p>順位: (1) 消化率昇順 → (2) campaign.id 昇順。完全一意。</p>
     *
     * @param operational 運用型候補（フィルタ済み）
     * @return 順位付け済みリスト（先頭が最優先）
     */
    public List<Candidate> rankOperational(List<Candidate> operational) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }

    /**
     * キャンペーン内クリエイティブをラウンドロビンで 1 件選ぶ（正本 §7.2 STEP 2 順位付け 3）。
     *
     * <p>{@code creativeIdsAsc}（ads.id 昇順）の添字 = {@code rrValue mod N} を返す。</p>
     *
     * @param creativeIdsAsc ads.id 昇順のクリエイティブ id リスト（非空）
     * @param rrValue        Valkey INCR の現在値
     * @return 選択された ads.id
     */
    public Long pickCreativeByRoundRobin(List<Long> creativeIdsAsc, long rrValue) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }

    /**
     * 順位付け済み候補列から count 件を重複回避規則で選ぶ（正本 §7.2 count=2 の重複回避規則）。
     *
     * <p>優先度順に適用し、満たせない場合のみ緩和する:
     * (a) 同一クリエイティブは重複させない（絶対禁止）／
     * (b) 同一広告主は重複させない（全候補が同一広告主なら緩和）／
     * (c) AFFILIATE 2 枠は provider を分ける。無理に埋めず候補が足りなければ少数で返す。</p>
     *
     * @param ranked 予約 → 運用型 → アフィリエイトの順で連結済みの順位付き候補列
     * @param count  返却上限（1〜2）
     * @return 選択された候補（長さ 0〜count）
     */
    public List<Candidate> selectWithCount(List<Candidate> ranked, int count) {
        throw new UnsupportedOperationException("F09.19.2 未実装（試練 red）");
    }

    private SpotlightAllocationSelector() {
        // 純粋関数ユーティリティだが、テストでインスタンス化して呼ぶため public コンストラクタは持たせない。
        // 実装時にステートレス @Component 化するか static メソッド化するかは出陣で決める。
    }

    /** テスト・呼び出し側からインスタンスを得るためのファクトリ（実装時に置換可）。 */
    public static SpotlightAllocationSelector create() {
        return new SpotlightAllocationSelector();
    }
}
