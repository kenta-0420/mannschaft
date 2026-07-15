package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.controller.SpotlightController;
import com.mannschaft.app.advertising.dto.SpotlightViewRequest;
import com.mannschaft.app.advertising.dto.SpotlightViewResponse;
import com.mannschaft.app.advertising.dto.SpotlightVisitRequest;
import com.mannschaft.app.advertising.dto.SpotlightVisitResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.2 view / visit 計上 API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §6.3・§6.4・§11・§16 F09.19.2。</p>
 *
 * <p>金型: {@code OperationalAdCampaignCrudIT}（直接 Controller 呼び出し + SecurityContext 認証 +
 * ネイティブ SQL フィクスチャ）。サービス骨格が {@link UnsupportedOperationException} を投げるため全 red。
 * serve 証跡は正本 §6.2 のキー体系 {@code mannschaft:ad:serve-token:{userId}:{creativeId}} を
 * in-memory Valkey フェイクに直接 seed して表現する（best-effort・green 化用）。</p>
 *
 * <p>AC 対応（メソッド名の ac 番号と 1:1）:</p>
 * <ul>
 *   <li>AC-2.7 serve 証跡なしの view/visit → 404・記録なし</li>
 *   <li>AC-2.8 view の dedupe / placement 整合 / 予約充足</li>
 *   <li>AC-2.9 deliveryId 帰属（他人の予約行）→ 404・未充足</li>
 *   <li>AC-2.10 visit の記録 / クールダウン / IP レート制限</li>
 *   <li>AC-2.11 creative とキャンペーン不一致 → 400 / AD_026</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.2 view/visit 計上 API 契約テスト（試練）")
class SpotlightMeasurementIT extends AbstractSpotlightIT {

    @Autowired
    private SpotlightController controller;

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");
    private static final String TILE = "DASHBOARD_TILE";

    private Long viewerId;
    private Long advOrgId;
    private Long advAccountId;
    private Long creatorId;
    private Long creativeId;
    private Long campaignId;

    @BeforeEach
    void setUp() {
        setUpCommon();
        viewerId = insertUser("measure-viewer@example.com");
        creatorId = insertUser("measure-owner@example.com");
        advOrgId = insertOrganization("F09192計測 広告主組織");
        advAccountId = insertAdvertiserAccount(advOrgId, "F09192計測 広告主");
        insertUserAdPreferences(viewerId, true, "[]");
        creativeId = insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "計測対象");
        campaignId = campaignIdOfCreative(creativeId);
        em.flush();
        setAuthentication(viewerId);
    }

    private void seedServeToken(Long userId, Long creative) {
        redisStore.put("mannschaft:ad:serve-token:" + userId + ":" + creative, "1");
    }

    private ResponseEntity<ApiResponse<SpotlightViewResponse>> view(Long creative, SpotlightViewRequest req) {
        return controller.view(creative, req);
    }

    private ResponseEntity<ApiResponse<SpotlightVisitResponse>> visit(
            Long creative, SpotlightVisitRequest req, HttpServletRequest http) {
        return controller.visit(creative, req, http);
    }

    private static HttpServletRequest requestFromIp(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        return req;
    }

    private SpotlightViewRequest operationalView() {
        return new SpotlightViewRequest(TILE, campaignId, null, null);
    }

    private SpotlightVisitRequest operationalVisit(Long impressionId) {
        return new SpotlightVisitRequest(TILE, impressionId, campaignId, null, null);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.7 serve 証跡なし
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_7: serve されていない creative への view → 404 かつ ad_impressions に行が増えない")
    void ac2_7_証跡なしviewは404_記録なし() {
        // serve-token を seed しない
        assertThatThrownBy(() -> view(creativeId, operationalView()))
                .as("serve 証跡なしは記録せず 404 相当の BusinessException")
                .isInstanceOf(BusinessException.class);
        assertThat(countImpressions(creativeId)).as("impression 行が増えない").isZero();
    }

    @Test
    @DisplayName("ac2_7: serve されていない creative への visit → 404 かつ ad_clicks に行が増えない")
    void ac2_7_証跡なしvisitは404_記録なし() {
        assertThatThrownBy(() -> visit(creativeId, operationalVisit(null), requestFromIp("203.0.113.1")))
                .isInstanceOf(BusinessException.class);
        assertThat(countClicks(creativeId)).as("click 行が増えない").isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.8 view
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_8: serve 済み creative に同一 user×creative を 600 秒内 2 回 → 2 回目 duplicate:true・既存 impressionId・1 行")
    void ac2_8_view二重計上防止() {
        seedServeToken(viewerId, creativeId);

        ResponseEntity<ApiResponse<SpotlightViewResponse>> first = view(creativeId, operationalView());
        assertThat(first.getStatusCode().value()).as("初回は 201").isEqualTo(201);
        SpotlightViewResponse firstBody = first.getBody().getData();
        assertThat(firstBody.duplicate()).isFalse();
        assertThat(firstBody.impressionId()).isNotNull();

        ResponseEntity<ApiResponse<SpotlightViewResponse>> second = view(creativeId, operationalView());
        assertThat(second.getStatusCode().value()).as("重複は 200").isEqualTo(200);
        SpotlightViewResponse secondBody = second.getBody().getData();
        assertThat(secondBody.duplicate()).as("2 回目は duplicate:true").isTrue();
        assertThat(secondBody.impressionId()).as("既存 impressionId を返す").isEqualTo(firstBody.impressionId());

        assertThat(countImpressions(creativeId)).as("ad_impressions は 1 行のみ").isEqualTo(1);
    }

    @Test
    @DisplayName("ac2_8: リクエスト placement が ads.placement と不一致 → 400 / AD_003")
    void ac2_8_placement不一致はAD_003() {
        seedServeToken(viewerId, creativeId);
        SpotlightViewRequest mismatched = new SpotlightViewRequest("IN_FEED", campaignId, null, null);

        assertThatThrownBy(() -> view(creativeId, mismatched))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AdvertisingErrorCode.AD_003));
    }

    @Test
    @DisplayName("ac2_8: 予約バナーの view → ad_banner_deliveries.served_at / ad_impression_id が充足される")
    void ac2_8_予約バナーviewで予約行充足() {
        ReservationFixture reservation = insertReservationBanner(advOrgId, advAccountId, viewerId, TILE, creatorId);
        em.flush();
        seedServeToken(viewerId, reservation.creativeId());

        SpotlightViewRequest req = new SpotlightViewRequest(
                TILE, null, reservation.messagingCampaignId(), reservation.deliveryId());

        assertThat(isDeliveryServed(reservation.deliveryId())).as("view 前は未充足").isFalse();

        ResponseEntity<ApiResponse<SpotlightViewResponse>> res = view(reservation.creativeId(), req);
        assertThat(res.getStatusCode().value()).isEqualTo(201);

        em.flush();
        em.clear();
        assertThat(isDeliveryServed(reservation.deliveryId()))
                .as("view 後は served_at / ad_impression_id が充足").isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.9 deliveryId 帰属
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_9: 他ユーザーの ad_banner_deliveries.id を指定した view → 404 かつ当該行は未充足のまま")
    void ac2_9_他人の予約行viewは404_未充足() {
        Long otherUser = insertUser("measure-other@example.com");
        ReservationFixture othersReservation =
                insertReservationBanner(advOrgId, advAccountId, otherUser, TILE, creatorId);
        em.flush();
        // 認証ユーザー(viewer)には serve 証跡を与える（証跡 404 ではなく帰属 404 を分離するため）
        seedServeToken(viewerId, othersReservation.creativeId());

        SpotlightViewRequest req = new SpotlightViewRequest(
                TILE, null, othersReservation.messagingCampaignId(), othersReservation.deliveryId());

        assertThatThrownBy(() -> view(othersReservation.creativeId(), req))
                .as("他人の予約行は帰属検証で 404")
                .isInstanceOf(BusinessException.class);
        assertThat(isDeliveryServed(othersReservation.deliveryId()))
                .as("当該予約行は未充足のまま").isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.10 visit
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_10: visit → ad_clicks 1 行 + impressionId 紐付け")
    void ac2_10_visitで1行記録_impression紐付け() {
        seedServeToken(viewerId, creativeId);
        // 先に view して impression を採番（紐付け対象）
        SpotlightViewResponse imp = view(creativeId, operationalView()).getBody().getData();

        ResponseEntity<ApiResponse<SpotlightVisitResponse>> res =
                visit(creativeId, operationalVisit(imp.impressionId()), requestFromIp("203.0.113.10"));

        assertThat(res.getStatusCode().value()).as("記録時は 201").isEqualTo(201);
        assertThat(res.getBody().getData().clickId()).isNotNull();
        assertThat(countClicks(creativeId)).as("ad_clicks 1 行").isEqualTo(1);
        Object linked = em.createNativeQuery("SELECT impression_id FROM ad_clicks WHERE ad_id = :id")
                .setParameter("id", creativeId).getSingleResult();
        assertThat(((Number) linked).longValue()).as("impressionId が紐付く").isEqualTo(imp.impressionId());
    }

    @Test
    @DisplayName("ac2_10: 同一 user×creative の 60 秒内 2 回目 → 200・clickId:null・行は増えない")
    void ac2_10_visitクールダウン() {
        seedServeToken(viewerId, creativeId);
        visit(creativeId, operationalVisit(null), requestFromIp("203.0.113.11"));

        ResponseEntity<ApiResponse<SpotlightVisitResponse>> second =
                visit(creativeId, operationalVisit(null), requestFromIp("203.0.113.11"));

        assertThat(second.getStatusCode().value()).as("クールダウン中は 200").isEqualTo(200);
        assertThat(second.getBody().getData().clickId()).as("clickId:null").isNull();
        assertThat(countClicks(creativeId)).as("行は 1 のまま増えない").isEqualTo(1);
    }

    @Test
    @DisplayName("ac2_10: 同一 IP から 60 秒に 11 回（別ユーザー）→ 11 回目 429 / AD_029")
    void ac2_10_IPレート制限() {
        String sharedIp = "203.0.113.100";
        // 10 回は別ユーザー・別 creative で通す（各自 serve 証跡を与える）。11 回目で IP 上限超過
        for (int i = 0; i < 10; i++) {
            Long u = insertUser("rl-user-" + i + "@example.com");
            Long c = insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "RL候補" + i);
            Long camp = campaignIdOfCreative(c);
            em.flush();
            seedServeToken(u, c);
            setAuthentication(u);
            visit(c, new SpotlightVisitRequest(TILE, null, camp, null, null), requestFromIp(sharedIp));
        }

        Long u11 = insertUser("rl-user-11@example.com");
        Long c11 = insertServableHouseCandidate(advOrgId, advAccountId, TILE, UNIT_PRICE, "RL候補11");
        Long camp11 = campaignIdOfCreative(c11);
        em.flush();
        seedServeToken(u11, c11);
        setAuthentication(u11);

        assertThatThrownBy(() -> visit(c11, new SpotlightVisitRequest(TILE, null, camp11, null, null),
                requestFromIp(sharedIp)))
                .as("同一 IP 11 回目は 429 / AD_029")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AdvertisingErrorCode.AD_029));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2.11 creative とキャンペーン不一致
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ac2_11: creativeId とキャンペーン不一致 → 400 / AD_026")
    void ac2_11_creativeとcampaign不一致はAD_026() {
        seedServeToken(viewerId, creativeId);
        // creativeId は campaignId に属する。別キャンペーン id を送って不一致にする
        Long otherCampaign = insertActiveOperationalCampaign(advOrgId, "別キャンペーン", "ACTIVE", UNIT_PRICE);
        em.flush();
        SpotlightViewRequest mismatched = new SpotlightViewRequest(TILE, otherCampaign, null, null);

        assertThatThrownBy(() -> view(creativeId, mismatched))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(AdvertisingErrorCode.AD_026));
    }
}
