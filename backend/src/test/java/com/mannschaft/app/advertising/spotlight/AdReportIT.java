package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.advertising.campaign.controller.MeAdReportController;
import com.mannschaft.app.advertising.campaign.controller.SystemAdminAdUserReportController;
import com.mannschaft.app.advertising.campaign.dto.AdReportCreatedResponse;
import com.mannschaft.app.advertising.campaign.dto.AdUserReportAdminResponse;
import com.mannschaft.app.advertising.campaign.dto.CreateAdReportRequest;
import com.mannschaft.app.advertising.campaign.dto.UpdateAdReportStatusRequest;
import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import com.mannschaft.app.advertising.campaign.enums.AdReportReasonCode;
import com.mannschaft.app.advertising.campaign.enums.AdReportStatus;
import com.mannschaft.app.advertising.controller.SystemAdminOperationalAdCampaignController;
import com.mannschaft.app.advertising.service.OperationalAdCampaignService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.19.9 通報 API 一式（メッセージ型/運用型）契約テスト（試練 / red 先行）。
 *
 * <p>正本 {@code docs/features/F09.19_ad_slot_serving.md} §12 / §16 F09.19.9。
 * 金型 {@link AbstractSpotlightIT}（直接 Controller/Service 呼び出し + SecurityContext 認証 +
 * ネイティブ SQL フィクスチャ）。</p>
 *
 * <p>AC 対応（メソッド名の ac 番号と 1:1）:
 * <ul>
 *   <li>AC-9.1 運用型通報 201・operational_campaign_id 行</li>
 *   <li>AC-9.2 メッセージ型通報 201（幻 API 404 根治）</li>
 *   <li>AC-9.3 3 ユーザー通報 → PAUSED + report_suspended_at + resume 403/AD_033</li>
 *   <li>AC-9.4 ENDED 巻き戻らない・広告主 PAUSED は status 不変</li>
 *   <li>AC-9.5 unsuspend 復帰・NULL 対象 409/AD_027・非 admin 403</li>
 *   <li>AC-9.6 メッセージ型 3 件 → BLOCKED</li>
 *   <li>AC-9.7 通報一覧 status 遷移・非 admin 403</li>
 *   <li>AC-9.8 両 ID 400/AD_032・不存在 404</li>
 *   <li>AC-9.9 同一ユーザー 2 回 = 2 行・RESOLVED/DISMISSED は 3 件カウント除外</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.9 通報 API 一式 契約テスト（試練）")
class AdReportIT extends AbstractSpotlightIT {

    private static final BigDecimal UNIT_PRICE = new BigDecimal("500.0000");

    @Autowired
    private MeAdReportController meAdReportController;
    @Autowired
    private SystemAdminAdUserReportController adminReportController;
    @Autowired
    private SystemAdminOperationalAdCampaignController operationalController;
    @Autowired
    private OperationalAdCampaignService operationalService;

    private Long advOrgId;
    private Long advAccountId;
    private Long reporter1;
    private Long reporter2;
    private Long reporter3;
    private Long adminUserId;
    private Long normalUserId;

    @BeforeEach
    void setUp() {
        setUpCommon();
        advOrgId = insertOrganization("F09199 通報 広告主組織");
        advAccountId = insertAdvertiserAccount(advOrgId, "F09199 通報 広告主");
        reporter1 = insertUser("report1@example.com");
        reporter2 = insertUser("report2@example.com");
        reporter3 = insertUser("report3@example.com");
        normalUserId = insertUser("normal@example.com");
        adminUserId = insertUser("sysadmin@example.com");
        insertRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertUserRole(adminUserId, roleId("SYSTEM_ADMIN"), null, null);
        em.flush();
    }

    // ═══════════════════════════ ヘルパー ═══════════════════════════

    private AdReportCreatedResponse reportOperational(Long userId, Long opCampaignId, AdReportReasonCode reason) {
        setAuthentication(userId);
        ResponseEntity<ApiResponse<AdReportCreatedResponse>> res = meAdReportController.create(
                new CreateAdReportRequest(null, opCampaignId, AdChannelType.BANNER, reason, null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().getData();
    }

    private AdReportCreatedResponse reportMessaging(Long userId, String messagingCampaignId, AdReportReasonCode reason) {
        setAuthentication(userId);
        ResponseEntity<ApiResponse<AdReportCreatedResponse>> res = meAdReportController.create(
                new CreateAdReportRequest(UUID.fromString(messagingCampaignId), null,
                        AdChannelType.BANNER, reason, null));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return res.getBody().getData();
    }

    private String operationalStatus(Long campaignId) {
        return (String) em.createNativeQuery("SELECT status FROM ad_campaigns WHERE id = :id")
                .setParameter("id", campaignId).getSingleResult();
    }

    private boolean operationalSuspended(Long campaignId) {
        Object v = em.createNativeQuery("SELECT report_suspended_at FROM ad_campaigns WHERE id = :id")
                .setParameter("id", campaignId).getSingleResult();
        return v != null;
    }

    private String messagingStatus(String campaignId) {
        return (String) em.createNativeQuery(
                        "SELECT status FROM ad_messaging_campaigns WHERE id = UUID_TO_BIN(:id)")
                .setParameter("id", campaignId).getSingleResult();
    }

    private long countReportsForOperational(Long campaignId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ad_user_reports WHERE operational_campaign_id = :id")
                .setParameter("id", campaignId).getSingleResult()).longValue();
    }

    /** ad_messaging_campaigns を指定 status で 1 行挿入する（BANNER 予約は不要な最小構成）。 */
    private String insertMessagingCampaign(String status) {
        String uuid = UUID.randomUUID().toString();
        em.createNativeQuery(
                        "INSERT INTO ad_messaging_campaigns (id, advertiser_account_id, scope_type, scope_id, name, "
                                + "status, total_budget_yen, consumed_budget_yen, starts_at, ends_at, "
                                + "scheduled_timezone, moderation_status, created_by_user_id, created_at, updated_at) "
                                + "VALUES (UUID_TO_BIN(:cid), :aid, 'ORGANIZATION', :oid, '通報対象キャンペーン', :st, "
                                + "100000, 0, DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), "
                                + "'Asia/Tokyo', 'APPROVED', :creator, NOW(), NOW())")
                .setParameter("cid", uuid).setParameter("aid", advAccountId).setParameter("oid", advOrgId)
                .setParameter("st", status).setParameter("creator", adminUserId)
                .executeUpdate();
        return uuid;
    }

    // ═══════════════════════════ AC-9.1 ═══════════════════════════

    @Test
    @DisplayName("AC-9.1 運用型通報は 201・operational_campaign_id 付きの行が作られる")
    void ac1_operationalReport201() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "運用型対象", "ACTIVE", UNIT_PRICE);
        em.flush();

        AdReportCreatedResponse res = reportOperational(reporter1, campaignId, AdReportReasonCode.MISLEADING);
        em.flush();

        assertThat(res.status()).isEqualTo(AdReportStatus.NEW);
        assertThat(res.id()).isNotNull();
        assertThat(countReportsForOperational(campaignId)).isEqualTo(1);
    }

    // ═══════════════════════════ AC-9.2 ═══════════════════════════

    @Test
    @DisplayName("AC-9.2 メッセージ型通報は 201（幻 API 404 根治）・campaign_id 付きの行が作られる")
    void ac2_messagingReport201() {
        String campaignUuid = insertMessagingCampaign("DELIVERING");
        em.flush();

        AdReportCreatedResponse res = reportMessaging(reporter1, campaignUuid, AdReportReasonCode.SPAM);
        em.flush();

        assertThat(res.status()).isEqualTo(AdReportStatus.NEW);
        long rows = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ad_user_reports WHERE campaign_id = UUID_TO_BIN(:id)")
                .setParameter("id", campaignUuid).getSingleResult()).longValue();
        assertThat(rows).isEqualTo(1);
    }

    // ═══════════════════════════ AC-9.3 ═══════════════════════════

    @Test
    @DisplayName("AC-9.3 ACTIVE に 3 ユーザー通報 → PAUSED + report_suspended_at・広告主 resume は 403/AD_033")
    void ac3_threeUsersReportOperational_pausedSuspendedResume403() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "自動停止対象", "ACTIVE", UNIT_PRICE);
        em.flush();

        reportOperational(reporter1, campaignId, AdReportReasonCode.MISLEADING);
        reportOperational(reporter2, campaignId, AdReportReasonCode.OFFENSIVE);
        reportOperational(reporter3, campaignId, AdReportReasonCode.SPAM);
        em.flush();
        em.clear();

        assertThat(operationalStatus(campaignId)).isEqualTo("PAUSED");
        assertThat(operationalSuspended(campaignId)).isTrue();

        // 広告主 resume は自動停止中のため 403/AD_033
        assertThatThrownBy(() ->
                operationalService.resume(ScopeType.ORGANIZATION, advOrgId, campaignId, reporter1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("AD_033");
    }

    // ═══════════════════════════ AC-9.4 ═══════════════════════════

    @Test
    @DisplayName("AC-9.4 ENDED は巻き戻らない・広告主 PAUSED は status 不変（いずれも report_suspended_at のみ）")
    void ac4_endedNotRolledBack_advertiserPausedUnchanged() {
        Long ended = insertActiveOperationalCampaign(advAccountId, "終了済み", "ENDED", UNIT_PRICE);
        Long paused = insertActiveOperationalCampaign(advAccountId, "広告主停止", "PAUSED", UNIT_PRICE);
        em.flush();

        for (Long u : new Long[]{reporter1, reporter2, reporter3}) {
            reportOperational(u, ended, AdReportReasonCode.SPAM);
        }
        for (Long u : new Long[]{reporter1, reporter2, reporter3}) {
            reportOperational(u, paused, AdReportReasonCode.SPAM);
        }
        em.flush();
        em.clear();

        assertThat(operationalStatus(ended)).isEqualTo("ENDED");
        assertThat(operationalSuspended(ended)).isTrue();

        assertThat(operationalStatus(paused)).isEqualTo("PAUSED");
        assertThat(operationalSuspended(paused)).isTrue();

        // 広告主 PAUSED は自動停止でも report_auto_paused=false のまま → unsuspend しても PAUSED 維持
        setAuthentication(adminUserId);
        operationalController.unsuspend(paused);
        em.flush();
        em.clear();
        assertThat(operationalStatus(paused)).isEqualTo("PAUSED");
        assertThat(operationalSuspended(paused)).isFalse();
    }

    // ═══════════════════════════ AC-9.5 ═══════════════════════════

    @Test
    @DisplayName("AC-9.5 unsuspend で ACTIVE 復帰・NULL 対象は 409/AD_027・非 admin は 403")
    void ac5_unsuspendRestore_nullTarget409_nonAdmin403() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "解除対象", "ACTIVE", UNIT_PRICE);
        Long neverSuspended = insertActiveOperationalCampaign(advAccountId, "未停止", "ACTIVE", UNIT_PRICE);
        em.flush();

        reportOperational(reporter1, campaignId, AdReportReasonCode.SPAM);
        reportOperational(reporter2, campaignId, AdReportReasonCode.SPAM);
        reportOperational(reporter3, campaignId, AdReportReasonCode.SPAM);
        em.flush();
        em.clear();
        assertThat(operationalStatus(campaignId)).isEqualTo("PAUSED");

        // SYSTEM_ADMIN の unsuspend → ACTIVE 復帰・report_suspended_at NULL
        setAuthentication(adminUserId);
        operationalController.unsuspend(campaignId);
        em.flush();
        em.clear();
        assertThat(operationalStatus(campaignId)).isEqualTo("ACTIVE");
        assertThat(operationalSuspended(campaignId)).isFalse();

        // 復帰後は広告主 resume 拒否が解ける（AD_033 が出ないこと）→ ここでは既に ACTIVE のため状態のみ確認済み

        // NULL 対象（未停止）への unsuspend → 409/AD_027
        setAuthentication(adminUserId);
        assertThatThrownBy(() -> operationalController.unsuspend(neverSuspended))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("AD_027");

        // 非 admin の unsuspend → 403（COMMON_002）
        setAuthentication(normalUserId);
        assertThatThrownBy(() -> operationalController.unsuspend(campaignId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("COMMON_002");
    }

    // ═══════════════════════════ AC-9.6 ═══════════════════════════

    @Test
    @DisplayName("AC-9.6 メッセージ型に 3 件通報 → status=BLOCKED")
    void ac6_messagingThreeReportsBlocked() {
        String campaignUuid = insertMessagingCampaign("DELIVERING");
        em.flush();

        reportMessaging(reporter1, campaignUuid, AdReportReasonCode.OFFENSIVE);
        reportMessaging(reporter2, campaignUuid, AdReportReasonCode.OFFENSIVE);
        reportMessaging(reporter3, campaignUuid, AdReportReasonCode.OFFENSIVE);
        em.flush();
        em.clear();

        assertThat(messagingStatus(campaignUuid)).isEqualTo("BLOCKED");
    }

    // ═══════════════════════════ AC-9.7 ═══════════════════════════

    @Test
    @DisplayName("AC-9.7 SYSTEM_ADMIN は通報一覧取得と NEW→REVIEWING→RESOLVED 遷移が可能・非 admin は 403")
    void ac7_adminListStatusTransition_nonAdmin403() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "一覧対象", "ACTIVE", UNIT_PRICE);
        em.flush();
        AdReportCreatedResponse created = reportOperational(reporter1, campaignId, AdReportReasonCode.MISLEADING);
        em.flush();

        // 一覧取得（SYSTEM_ADMIN）
        setAuthentication(adminUserId);
        PagedResponse<AdUserReportAdminResponse> list =
                adminReportController.list(null, null, 0, 20);
        assertThat(list.getData()).isNotEmpty();

        // NEW → REVIEWING → RESOLVED
        setAuthentication(adminUserId);
        AdUserReportAdminResponse reviewing = adminReportController.updateStatus(
                created.id(), new UpdateAdReportStatusRequest(AdReportStatus.REVIEWING)).getData();
        assertThat(reviewing.status()).isEqualTo(AdReportStatus.REVIEWING);

        AdUserReportAdminResponse resolved = adminReportController.updateStatus(
                created.id(), new UpdateAdReportStatusRequest(AdReportStatus.RESOLVED)).getData();
        assertThat(resolved.status()).isEqualTo(AdReportStatus.RESOLVED);

        // 非 admin の一覧取得 → 403
        setAuthentication(normalUserId);
        assertThatThrownBy(() -> adminReportController.list(null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("COMMON_002");
    }

    // ═══════════════════════════ AC-9.8 ═══════════════════════════

    @Test
    @DisplayName("AC-9.8 両 ID 指定/両 null は 400/AD_032・不存在 operationalCampaignId は 404/AD_021")
    void ac8_bothIds400_nonexistent404() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "XOR対象", "ACTIVE", UNIT_PRICE);
        String messagingUuid = insertMessagingCampaign("DELIVERING");
        em.flush();

        setAuthentication(reporter1);
        // 両方指定 → AD_032
        assertThatThrownBy(() -> meAdReportController.create(new CreateAdReportRequest(
                UUID.fromString(messagingUuid), campaignId, AdChannelType.BANNER,
                AdReportReasonCode.SPAM, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("AD_032");

        // 両方 null → AD_032
        assertThatThrownBy(() -> meAdReportController.create(new CreateAdReportRequest(
                null, null, AdChannelType.BANNER, AdReportReasonCode.SPAM, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("AD_032");

        // 不存在 operationalCampaignId → AD_021（404）
        assertThatThrownBy(() -> meAdReportController.create(new CreateAdReportRequest(
                null, 99999999L, AdChannelType.BANNER, AdReportReasonCode.SPAM, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                .isEqualTo("AD_021");
    }

    // ═══════════════════════════ AC-9.9 ═══════════════════════════

    @Test
    @DisplayName("AC-9.9 同一ユーザー 2 回で 2 行・RESOLVED/DISMISSED は 3 件カウントから除外")
    void ac9_sameUserTwoRows_resolvedDismissedExcluded() {
        Long campaignId = insertActiveOperationalCampaign(advAccountId, "カウント対象", "ACTIVE", UNIT_PRICE);
        em.flush();

        // 同一ユーザーが 2 回 → 2 行（重複排除しない）
        AdReportCreatedResponse r1 = reportOperational(reporter1, campaignId, AdReportReasonCode.SPAM);
        AdReportCreatedResponse r2 = reportOperational(reporter1, campaignId, AdReportReasonCode.SPAM);
        em.flush();
        assertThat(countReportsForOperational(campaignId)).isEqualTo(2);
        // まだ 2 件のため自動停止しない
        em.clear();
        assertThat(operationalStatus(campaignId)).isEqualTo("ACTIVE");

        // 2 件を RESOLVED / DISMISSED にすると未処理カウントは 0 になる
        setAuthentication(adminUserId);
        adminReportController.updateStatus(r1.id(), new UpdateAdReportStatusRequest(AdReportStatus.REVIEWING));
        adminReportController.updateStatus(r1.id(), new UpdateAdReportStatusRequest(AdReportStatus.RESOLVED));
        adminReportController.updateStatus(r2.id(), new UpdateAdReportStatusRequest(AdReportStatus.REVIEWING));
        adminReportController.updateStatus(r2.id(), new UpdateAdReportStatusRequest(AdReportStatus.DISMISSED));
        em.flush();

        // 新たに 3 件（NEW）通報 → NEW/REVIEWING が 3 件で自動停止（RESOLVED/DISMISSED の 2 件は数えない）
        reportOperational(reporter1, campaignId, AdReportReasonCode.SPAM);
        reportOperational(reporter2, campaignId, AdReportReasonCode.SPAM);
        reportOperational(reporter3, campaignId, AdReportReasonCode.SPAM);
        em.flush();
        em.clear();
        assertThat(operationalStatus(campaignId)).isEqualTo("PAUSED");
        assertThat(operationalSuspended(campaignId)).isTrue();
    }
}
