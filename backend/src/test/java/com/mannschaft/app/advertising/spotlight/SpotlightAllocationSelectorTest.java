package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.advertising.service.SpotlightAllocationSelector;
import com.mannschaft.app.advertising.service.SpotlightAllocationSelector.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.19.2 割当ロジックの純ロジック ドメイン UT（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §7.2（割当ロジック・順位付け・
 * count=2 重複回避規則）・§16 F09.19.2（AC-2.4 順位 / AC-2.5 count=2）。</p>
 *
 * <p>DB / Valkey / Spring から独立した決定的ロジックのみを検証する。
 * {@link SpotlightAllocationSelector} は骨格（{@link UnsupportedOperationException}）のため
 * 全テストは red。出陣で §7.2 のロジックを充填して green 化する。</p>
 *
 * <p>AC 対応（メソッド名の ac 番号と 1:1）:</p>
 * <ul>
 *   <li>AC-2.4 消化率昇順 → campaign.id 昇順 / クリエイティブ ラウンドロビン</li>
 *   <li>AC-2.5 count=2 重複回避（異広告主非重複 / 同一広告主のみ緩和 / 候補 1 件）</li>
 * </ul>
 */
@DisplayName("F09.19.2 割当ロジック 純ロジック UT（試練）")
class SpotlightAllocationSelectorTest {

    private final SpotlightAllocationSelector selector = SpotlightAllocationSelector.create();

    private static Candidate house(long creativeId, long campaignId, long advertiserId, String spendRatio) {
        return new Candidate("HOUSE", creativeId, campaignId, advertiserId,
                spendRatio == null ? null : new BigDecimal(spendRatio), null, false);
    }

    private static Candidate affiliate(String provider) {
        return new Candidate("AFFILIATE", null, null, null, null, provider, false);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.4 順位付け
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2.4 順位付け（消化率昇順 → campaign.id 昇順 / ラウンドロビン）")
    class Ac2_4_Ranking {

        @Test
        @DisplayName("ac2_4: 消化率 0% と 50% の 2 キャンペーン → 0% が先頭（未消化優先ペーシング）")
        void ac2_4_消化率昇順で未消化が優先される() {
            Candidate ratio50 = house(10L, 100L, 1L, "0.50");
            Candidate ratio0 = house(20L, 200L, 2L, "0.00");

            List<Candidate> ranked = selector.rankOperational(List.of(ratio50, ratio0));

            assertThat(ranked).extracting(Candidate::campaignId)
                    .as("消化率 0% のキャンペーン(200)が先頭・50%(100)が次点")
                    .containsExactly(200L, 100L);
        }

        @Test
        @DisplayName("ac2_4: 同一消化率 → campaign.id 昇順で一意化")
        void ac2_4_同率はcampaignId昇順() {
            Candidate campBig = house(10L, 300L, 1L, "0.25");
            Candidate campSmall = house(20L, 150L, 2L, "0.25");

            List<Candidate> ranked = selector.rankOperational(List.of(campBig, campSmall));

            assertThat(ranked).extracting(Candidate::campaignId)
                    .as("同率は campaign.id 小(150)が先")
                    .containsExactly(150L, 300L);
        }

        @Test
        @DisplayName("ac2_4: クリエイティブ 2 件はラウンドロビンで交互（rr mod N）")
        void ac2_4_クリエイティブはラウンドロビンで交互() {
            List<Long> creativesAsc = List.of(11L, 22L); // ads.id 昇順

            // rr=0 → 添字0(11)、rr=1 → 添字1(22)、rr=2 → 添字0(11) と巡回する
            assertThat(selector.pickCreativeByRoundRobin(creativesAsc, 0L)).isEqualTo(11L);
            assertThat(selector.pickCreativeByRoundRobin(creativesAsc, 1L)).isEqualTo(22L);
            assertThat(selector.pickCreativeByRoundRobin(creativesAsc, 2L)).isEqualTo(11L);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.5 count=2 重複回避
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2.5 count=2 の重複回避規則")
    class Ac2_5_CountTwo {

        @Test
        @DisplayName("ac2_5: 異なる広告主 2 キャンペーン → items 2 件で広告主が重複しない")
        void ac2_5_異なる広告主は2件で非重複() {
            Candidate advA = house(10L, 100L, 1L, "0.00");
            Candidate advB = house(20L, 200L, 2L, "0.10");

            List<Candidate> selected = selector.selectWithCount(List.of(advA, advB), 2);

            assertThat(selected).hasSize(2);
            assertThat(selected).extracting(Candidate::advertiserAccountId)
                    .as("広告主 id が重複しない").doesNotHaveDuplicates();
            assertThat(selected).extracting(Candidate::creativeId)
                    .as("クリエイティブ id が重複しない").doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("ac2_5: 同一広告主のみ 2 キャンペーン → 緩和規則 b で同広告主 2 件")
        void ac2_5_同一広告主のみは緩和で2件() {
            Candidate sameAdv1 = house(10L, 100L, 7L, "0.00");
            Candidate sameAdv2 = house(20L, 200L, 7L, "0.10");

            List<Candidate> selected = selector.selectWithCount(List.of(sameAdv1, sameAdv2), 2);

            assertThat(selected).as("全候補が同一広告主なら b を緩和して 2 件返す").hasSize(2);
            assertThat(selected).extracting(Candidate::creativeId)
                    .as("クリエイティブ id（規則 a）は依然重複しない").doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("ac2_5: 候補 1 件のみ → items 1 件（無理に埋めない）")
        void ac2_5_候補1件は1件で返す() {
            Candidate only = house(10L, 100L, 1L, "0.00");

            List<Candidate> selected = selector.selectWithCount(List.of(only), 2);

            assertThat(selected).hasSize(1);
            assertThat(selected.get(0).creativeId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("ac2_5: AFFILIATE 2 枠は provider を分ける（AMAZON/RAKUTEN）")
        void ac2_5_アフィリエイト2枠はprovider分離() {
            Candidate amazon1 = affiliate("AMAZON");
            Candidate amazon2 = affiliate("AMAZON");
            Candidate rakuten = affiliate("RAKUTEN");

            List<Candidate> selected = selector.selectWithCount(List.of(amazon1, amazon2, rakuten), 2);

            assertThat(selected).hasSize(2);
            assertThat(selected).extracting(Candidate::provider)
                    .as("AFFILIATE 2 枠は provider が分かれる").containsExactlyInAnyOrder("AMAZON", "RAKUTEN");
        }
    }
}
