package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.controller.SpotlightController;
import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.dto.SpotlightItem;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.2 {@code GET /api/v1/spotlight/content} サービング API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §6.2・§7.1〜7.5・§16 F09.19.2。</p>
 *
 * <p>金型: {@code OperationalAdCampaignCrudIT}（直接 Controller 呼び出し + SecurityContext 認証 +
 * ネイティブ SQL フィクスチャ）。サービス骨格が {@link UnsupportedOperationException} を投げるため全 red。</p>
 *
 * <p>AC 対応（メソッド名の ac 番号と 1:1）:</p>
 * <ul>
 *   <li>AC-2.1 HOUSE / AFFILIATE / items:[] の source 決定</li>
 *   <li>AC-2.2 予約優先（reservation が items[0]）</li>
 *   <li>AC-2.3 受信設定 off / blocked / 日予算消化 / serve-cap 除外</li>
 *   <li>AC-2.5 count=2 の重複回避（IT 観測。純ロジックは AllocationSelectorTest）</li>
 *   <li>AC-2.6 有料プラン BE ゲート（TEAM/ORGANIZATION/PERSONAL の観測可能差分）</li>
 *   <li>AC-2.11 placement 不正・scopeId 欠落・未認証（content 側）</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.2 サービング content API 契約テスト（試練）")
class SpotlightContentServingIT extends AbstractSpotlightIT {

    @Autowired
    private SpotlightController controller;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final String TILE = "DASHBOARD_TILE";

    private Long viewerId;
    private Long advOrgId;
    private Long advAccountId;
    private Long creatorId;

    @BeforeEach
    void setUp() {
        setUpCommon();
        insertRole("MEMBER", "メンバー", 5, false);
        viewerId = insertUser("spotlight-viewer@example.com");
        creatorId = insertUser("spotlight-adv-owner@example.com");
        advOrgId = insertOrganization("F09192 広告主組織");
        advAccountId = insertAdvertiserAccount(advOrgId, "F09192 広告主");
        // 既定は受信 ON・ブロックなし
        insertUserAdPreferences(viewerId, true, "[]");
        em.flush();
        setAuthentication(viewerId);
    }

    private List<SpotlightItem> content(Integer count, String scopeType, Long scopeId) {
        SpotlightContentResponse body = controller
                .content(TILE, count, scopeType, scopeId, null, null, "ja")
                .getBody().getData();
        return body.items();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.1 source 決定
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_1: ACTIVE 運用型 × ACTIVE クリエイティブ（placement 一致）あり → items[0].source = HOUSE")
    void ac2_1_運用型ありはHOUSE() {
        insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "夏キャンペーン");
        em.flush();

        List<SpotlightItem> items = content(1, "PERSONAL", null);

        assertThat(items).as("候補 1 件").hasSize(1);
        assertThat(items.get(0).source()).isEqualTo("HOUSE");
        assertThat(items.get(0).house()).isNotNull();
        assertThat(items.get(0).house().advertiserAccountId()).isEqualTo(advAccountId);
    }

    @Test
    @DisplayName("ac2_1: 運用型なし・アフィリエイトあり → source = AFFILIATE")
    void ac2_1_運用型なしアフィリエイトありはAFFILIATE() {
        insertAffiliateConfig("AMAZON", TILE, 0);
        em.flush();

        List<SpotlightItem> items = content(1, "PERSONAL", null);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).source()).isEqualTo("AFFILIATE");
        assertThat(items.get(0).affiliate()).isNotNull();
        assertThat(items.get(0).affiliate().provider()).isEqualTo("AMAZON");
    }

    @Test
    @DisplayName("ac2_1: 運用型・アフィリエイト両方なし → items:[]")
    void ac2_1_候補なしは空配列() {
        List<SpotlightItem> items = content(1, "PERSONAL", null);
        assertThat(items).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.2 予約優先
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_2: F09.17 予約（served_at NULL・DELIVERING・placement 一致）と運用型が両方 → 予約が items[0]")
    void ac2_2_予約が最優先() {
        // 運用型候補
        insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "運用型候補");
        // 予約バナー候補
        ReservationFixture reservation = insertReservationBanner(advOrgId, advAccountId, viewerId, TILE, creatorId);
        em.flush();

        List<SpotlightItem> items = content(1, "PERSONAL", null);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).source()).isEqualTo("HOUSE");
        assertThat(items.get(0).house().deliveryId())
                .as("予約バナーのみ deliveryId を持つ → 予約が items[0]").isEqualTo(reservation.deliveryId());
        assertThat(items.get(0).house().messagingCampaignId())
                .isEqualTo(reservation.messagingCampaignId());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.3 フィルタ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2.3 受信設定 / blocked / 日予算 / serve-cap 除外")
    class Ac2_3_Filters {

        @Test
        @DisplayName("ac2_3: accept_banner_ads=false → HOUSE をスキップし AFFILIATE のみ")
        void ac2_3_受信オフはHOUSEスキップ() {
            // 受信設定を off に作り直す
            em.createNativeQuery("DELETE FROM user_ad_preferences WHERE user_id = :uid")
                    .setParameter("uid", viewerId).executeUpdate();
            insertUserAdPreferences(viewerId, false, "[]");
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "HOUSE候補");
            insertAffiliateConfig("RAKUTEN", TILE, 0);
            em.flush();

            List<SpotlightItem> items = content(1, "PERSONAL", null);

            assertThat(items).hasSize(1);
            assertThat(items.get(0).source()).as("HOUSE はスキップされ AFFILIATE のみ").isEqualTo("AFFILIATE");
        }

        @Test
        @DisplayName("ac2_3: blocked_advertiser_account_ids 該当広告主は候補除外 → items:[]")
        void ac2_3_ブロック広告主は除外() {
            em.createNativeQuery("DELETE FROM user_ad_preferences WHERE user_id = :uid")
                    .setParameter("uid", viewerId).executeUpdate();
            insertUserAdPreferences(viewerId, true, "[" + advAccountId + "]");
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "ブロック対象");
            em.flush();

            List<SpotlightItem> items = content(1, "PERSONAL", null);

            assertThat(items).as("ブロック広告主のみ → 候補ゼロ").isEmpty();
        }

        @Test
        @DisplayName("ac2_3: 日予算消化済み（Valkey カウンタ × 単価 ≥ daily_budget）キャンペーンは除外")
        void ac2_3_日予算消化済みは除外() {
            Long creativeId = insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "予算消化");
            em.flush();
            Long campaignId = campaignIdOfCreative(creativeId);

            // daily_budget=3000・単価=500 → 6 imp で 3000 到達。カウンタを 6 に前置き（正本 §7.3 のキー体系）
            String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            redisStore.put("mannschaft:ad:imps:" + campaignId + ":" + day, "6");

            List<SpotlightItem> items = content(1, "PERSONAL", null);

            assertThat(items).as("日予算消化済みキャンペーンは除外され候補ゼロ").isEmpty();
        }

        @Test
        @DisplayName("ac2_3: serve-cap 存在中（1 時間以内再訪）は同一キャンペーンを返さない")
        void ac2_3_serveCap存在中は同一キャンペーン非返却() {
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "再訪キャンペーン");
            em.flush();

            // 1 回目: serve される（実装が serve-cap を SETNX する）
            List<SpotlightItem> first = content(1, "PERSONAL", null);
            assertThat(first).as("初回は HOUSE が返る").hasSize(1);

            // 2 回目: serve-cap 存在中 → 同一キャンペーンは返らない（他候補なし → 空）
            List<SpotlightItem> second = content(1, "PERSONAL", null);
            assertThat(second).as("serve-cap 中は同一キャンペーン非返却").isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.5 count=2 の重複回避（IT 観測）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_5: count=2・異なる広告主 2 キャンペーン → items 2 件で広告主が重複しない")
    void ac2_5_異なる広告主2件は非重複() {
        Long advOrg2 = insertOrganization("F09192 広告主組織2");
        Long advAccount2 = insertAdvertiserAccount(advOrg2, "F09192 広告主2");
        insertServableHouseCandidate(advOrgId, advAccountId, TILE, new BigDecimal("100.0000"), "広告主A");
        insertServableHouseCandidate(advOrg2, advAccount2, TILE, new BigDecimal("100.0000"), "広告主B");
        em.flush();

        List<SpotlightItem> items = content(2, "PERSONAL", null);

        assertThat(items).hasSize(2);
        assertThat(items).extracting(i -> i.house().advertiserAccountId())
                .as("広告主が重複しない").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ac2_5: count=2・候補 1 件 → items 1 件（無理に埋めない）")
    void ac2_5_候補1件は1件() {
        insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "単独候補");
        em.flush();

        List<SpotlightItem> items = content(2, "PERSONAL", null);
        assertThat(items).hasSize(1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.6 有料プラン BE ゲート
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2.6 有料プラン BE ゲート（観測可能差分）")
    class Ac2_6_PaidPlanGate {

        @Test
        @DisplayName("ac2_6: TEAM が PACKAGE・ACTIVE → items:[] / EXPIRED に変えると候補が返る")
        void ac2_6_TEAM有料は空_失効で復帰() {
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "TEAMゲート候補");
            Long team = insertTeam("有料チーム");
            insertTeamSubscription(team, "PACKAGE", "ACTIVE");
            em.flush();

            assertThat(content(1, "TEAM", team)).as("有料チームの掲載面は items:[]").isEmpty();

            // サブスクを EXPIRED に変更 → ゲート解除で候補が返る
            em.createNativeQuery("UPDATE team_subscriptions SET status='EXPIRED' WHERE team_id = :tid")
                    .setParameter("tid", team).executeUpdate();
            em.flush();

            assertThat(content(1, "TEAM", team)).as("失効後は候補が返る").hasSize(1);
        }

        @Test
        @DisplayName("ac2_6: 配下チームに ORGANIZATION・ACTIVE を持つ組織の scopeType=ORGANIZATION → items:[]")
        void ac2_6_ORGANIZATION配下有料は空() {
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "ORGゲート候補");
            Long viewerOrg = insertOrganization("閲覧組織");
            Long childTeam = insertTeam("組織配下チーム");
            // findTeamIdsByOrganizationId: user_roles(active user, organization_id=viewerOrg, team_id=childTeam)
            insertUserRole(viewerId, roleId("MEMBER"), childTeam, viewerOrg);
            insertTeamSubscription(childTeam, "ORGANIZATION", "ACTIVE");
            em.flush();

            assertThat(content(1, "ORGANIZATION", viewerOrg))
                    .as("配下に ORGANIZATION プラン ACTIVE → items:[]").isEmpty();
        }

        @Test
        @DisplayName("ac2_6: 有料チームに所属する user の PERSONAL → items:[]")
        void ac2_6_PERSONAL所属有料は空() {
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "PERSONALゲート候補");
            Long paidTeam = insertTeam("所属有料チーム");
            insertTeamMembership(viewerId, paidTeam);
            insertTeamSubscription(paidTeam, "PACKAGE", "ACTIVE");
            em.flush();

            assertThat(content(1, "PERSONAL", null)).as("所属先が有料 → items:[]").isEmpty();
        }

        @Test
        @DisplayName("ac2_6: どの有料チームにも属さない user の PERSONAL → 候補が返る")
        void ac2_6_PERSONAL無料所属は候補返却() {
            insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "PERSONAL無料候補");
            Long freeTeam = insertTeam("所属無料チーム");
            insertTeamMembership(viewerId, freeTeam);
            insertTeamSubscription(freeTeam, "FREE", "ACTIVE");
            em.flush();

            assertThat(content(1, "PERSONAL", null)).as("有料チーム非所属 → 候補が返る").hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.11 異常系（content 側）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2.11 異常系（placement 不正・scopeId 欠落・未認証）")
    class Ac2_11_Errors {

        @Test
        @DisplayName("ac2_11: placement 不正値 → 400 / AD_003")
        void ac2_11_placement不正はAD_003() {
            assertThatThrownBy(() -> controller.content("NOT_A_PLACEMENT", 1, "PERSONAL", null, null, null, "ja"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_003));
        }

        @Test
        @DisplayName("ac2_11: scopeType=TEAM で scopeId 欠落 → 400 / AD_003")
        void ac2_11_scopeId欠落はAD_003() {
            assertThatThrownBy(() -> controller.content(TILE, 1, "TEAM", null, null, null, "ja"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(AdvertisingErrorCode.AD_003));
        }

        @Test
        @DisplayName("ac2_11: 未認証 → 401（@PreAuthorize(isAuthenticated) が拒否）")
        void ac2_11_未認証は401() {
            clearAuthentication();
            // /spotlight/content は認証必須（§6.2・§11）。メソッドセキュリティ層で拒否され、
            // Spring Security の AuthenticationException（→ 401）に解決される。
            assertThatThrownBy(() -> controller.content(TILE, 1, "PERSONAL", null, null, null, "ja"))
                    .as("未認証は 401 相当のセキュリティ例外で拒否される")
                    .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        }
    }
}
