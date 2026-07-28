package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」第二陣・部隊C（乙） — recruitment ドメイン NO_SHOW 異議解決の認可契約テスト。
 *
 * <p><b>狙う穴</b>: {@code PATCH /api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute}
 * は {@code RecruitmentNoShowService#resolveDispute} で「パス由来の親スコープ（{@code scopeId}）に対する
 * 管理者権限」だけを {@code accessControlService.checkAdminOrAbove} で検証し、続く
 * {@code noShowRepository.findById(recordId)} は<b>帰属検証なしの直引き</b>だった。
 * よって「自分の団体の ADMIN であること」しか要求されず、URL の {@code scopeId} を自分の団体に、
 * {@code noShowId} を他団体の記録 ID にすると、他団体の NO_SHOW 記録を書き換えられる
 * <b>テナント越境 BOLA（書き込み）</b>が成立していた。reservation のリマインダー 2 EP（#2469）と完全に同型。</p>
 *
 * <p><b>対称性の破れ</b>: 同一 Service の兄弟メソッド {@code getNoShowsByScope} は
 * {@code findByScopeTypeAndScopeId(scopeType, scopeId)} とスコープ済みクエリを使っており、
 * {@code resolveDispute} だけが規律を破っていた。根治は同ドメインの
 * {@code RecruitmentListingService#createFromTemplate}（{@code TEMPLATE_SCOPE_MISMATCH} の明示的帰属検証）
 * および {@code RecruitmentSubcategoryService}（{@code findByIdAndScopeTypeAndScopeId}）の型を踏襲し、
 * Repository にスコープ済み finder を追加して封じた。</p>
 *
 * <p><b>なぜ 404 でなく 400 か（AC-R8）</b>: {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に
 * 登録された {@code RECRUITMENT_*} は {@code RECRUITMENT_204} / {@code RECRUITMENT_207} の 2 件のみで、
 * どちらも 400。未登録コードは {@code resolveHttpStatus} の Severity 既定にフォールバックし、
 * {@code NO_SHOW_RECORD_NOT_FOUND}（{@code RECRUITMENT_309}・{@code Severity.WARN}）は
 * <b>HTTP 400</b> になる。本テストは実挙動に合わせて 400 を固定する。
 * 一方、認可拒否は {@code CommonErrorCode.COMMON_002} が {@code ERROR_CODE_STATUS_MAP} に
 * <b>403 で明示登録</b>されているため 403。{@code RECRUITMENT_*} を 404/403 に整理し直す統一は
 * FE 分岐・既存 E2E への波及を避けるため別課題（#2468）とする。</p>
 *
 * <p>金型: {@code ReservationScopeContractIT}（#2469）/ {@code ChatChannelAccessScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment NO_SHOW 異議解決 認可契約テスト（裏目付C・スコープ帰属の越境封鎖）")
class RecruitmentNoShowScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しない NO_SHOW 記録 ID（実在オラクル封じの対照）。 */
    private static final long ABSENT_NO_SHOW_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentNoShowRecordRepository noShowRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgCId;
    private Long orgDId;

    /** teamA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamA の非管理者メンバー（管理 EP には 403）。 */
    private Long memberAId;
    /** teamB の ADMIN（越境攻撃者役。teamA の URL には 403・自 URL + 他団体 ID には 400）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;
    /** orgC の ADMIN（ORGANIZATION スコープの正当な管理者）。 */
    private Long adminCId;

    /** teamA の募集に紐づく異議申立中 NO_SHOW 記録。 */
    private Long noShowAId;
    /** teamB の募集に紐づく異議申立中 NO_SHOW 記録（越境 ID として teamA の URL に差し込む主役）。 */
    private Long noShowBId;
    /** orgC の募集に紐づく異議申立中 NO_SHOW 記録。 */
    private Long noShowCId;
    /** orgD の募集に紐づく異議申立中 NO_SHOW 記録（ORGANIZATION 側の越境 ID）。 */
    private Long noShowDId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("RCRTAUTHZ チームA");
        teamBId = insertTeam("RCRTAUTHZ チームB");
        orgCId = insertOrganization("RCRTAUTHZ 組織C");
        orgDId = insertOrganization("RCRTAUTHZ 組織D");

        adminAId = insertUser("rcrtauthz-admin-a@example.com");
        memberAId = insertUser("rcrtauthz-member-a@example.com");
        adminBId = insertUser("rcrtauthz-admin-b@example.com");
        outsiderId = insertUser("rcrtauthz-outsider@example.com");
        adminCId = insertUser("rcrtauthz-admin-c@example.com");

        // isScopeAdmin（user_roles）と isMember（memberships）は別系統のため、
        // ADMIN にも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminCId, ScopeType.ORGANIZATION, orgCId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminCId, "ADMIN", null, orgCId);
        // outsiderId はどこにも所属させない。

        Long listingAId = insertListing(RecruitmentScopeType.TEAM, teamAId, adminAId);
        Long listingBId = insertListing(RecruitmentScopeType.TEAM, teamBId, adminBId);
        Long listingCId = insertListing(RecruitmentScopeType.ORGANIZATION, orgCId, adminCId);
        Long listingDId = insertListing(RecruitmentScopeType.ORGANIZATION, orgDId, adminCId);

        noShowAId = insertDisputedNoShow(listingAId, memberAId);
        noShowBId = insertDisputedNoShow(listingBId, adminBId);
        noShowCId = insertDisputedNoShow(listingCId, memberAId);
        noShowDId = insertDisputedNoShow(listingDId, memberAId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute（異議解決）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute（NO_SHOW異議解決）")
    class ResolveDispute {

        /**
         * AC-R1: 別スコープ（teamB）の noShowId を自スコープ（teamA）の URL に差し込むと遮断される。
         *
         * <p>攻撃者は teamA の正当な ADMIN であり {@code checkAdminOrAbove} は通過するため、
         * 記録側の帰属検証がなければ 200 で書き換えが成立する（根治前は red）。</p>
         */
        @Test
        @DisplayName("AC-R1: 正当ADMINが別スコープのnoShowIdを差し込むと400（越境BOLA封鎖）")
        void ac_r1_越境noShowIdは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode()));
        }

        /**
         * AC-R2: 遮断されたとき、対象 record が DB 上で書き換わっていないこと。
         *
         * <p>書き込み系 EP ゆえステータスだけでは不十分。{@code em.flush()} で
         * 永続化コンテキストの変更を DB へ押し出したうえで {@code em.clear()} して
         * 実際に DB から読み直し、{@code dispute_resolution} が NULL のままであることを照合する。</p>
         */
        @Test
        @DisplayName("AC-R2: 遮断時に別スコープのrecordがDB上で書き換わっていない")
        void ac_r2_遮断時にrecordは不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest());

            em.flush();
            em.clear();

            RecruitmentNoShowRecordEntity untouched = noShowRepository.findById(noShowBId).orElseThrow();
            assertThat(untouched.getDisputeResolution())
                    .as("越境の異議解決で teamB の NO_SHOW 記録が書き換えられてはならない")
                    .isNull();
            assertThat(untouched.isDisputed())
                    .as("異議申立中フラグも維持されること")
                    .isTrue();
        }

        /** AC-R3: 正当スコープ管理者の異議解決は 200 のまま（非回帰）。 */
        @Test
        @DisplayName("AC-R3: 正当スコープADMINの異議解決は200（非回帰）")
        void ac_r3_正当ADMINの異議解決は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            RecruitmentNoShowRecordEntity resolved = noShowRepository.findById(noShowAId).orElseThrow();
            assertThat(resolved.getDisputeResolution())
                    .as("正当スコープの異議解決は従来どおり反映されること")
                    .isEqualTo(DisputeResolution.REVOKED);
        }

        /** AC-R4: 非管理者メンバーは 403（{@code checkAdminOrAbove} → COMMON_002）。 */
        @Test
        @DisplayName("AC-R4: 非管理者メンバーは403")
        void ac_r4_非管理者メンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isForbidden());
        }

        /** AC-R5: 完全な部外者は 403。 */
        @Test
        @DisplayName("AC-R5: 部外者は403")
        void ac_r5_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isForbidden());
        }

        /** AC-R5（補強）: 別スコープ ADMIN が当該スコープ URL を叩いても 403（親スコープゲートが先に発火）。 */
        @Test
        @DisplayName("AC-R5: 別スコープADMINが当該スコープURLを叩くと403")
        void ac_r5_別スコープADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "TEAM", teamAId, noShowAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isForbidden());
        }

        /**
         * AC-R6: 実在オラクル封じ。「越境した実在 ID」と「そもそも存在しない ID」が
         * 同一ステータス・同一エラーコードで返ること。片方が 400 でもう片方が 403/404 だと
         * 応答差分から ID の実在が漏れる。
         */
        @Test
        @DisplayName("AC-R6: 越境の実在IDと不在IDが同一応答（実在オラクル封じ）")
        void ac_r6_越境IDと不在IDは同一応答() throws Exception {
            setAuth(adminAId);

            String crossTenantBody = mockMvc.perform(
                            patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                                    "TEAM", teamAId, noShowBId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            String absentBody = mockMvc.perform(
                            patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                                    "TEAM", teamAId, ABSENT_NO_SHOW_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossTenantBody)
                    .as("越境した実在IDと不在IDの応答本文は完全一致でなければならない（実在オラクル封じ）")
                    .isEqualTo(absentBody);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. ORGANIZATION スコープでも同じ帰属検証が効くこと
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. ORGANIZATION スコープの越境封鎖")
    class ResolveDisputeOrganizationScope {

        /** AC-R1（ORGANIZATION 版）: 別組織の noShowId を自組織 URL に差し込むと 400。 */
        @Test
        @DisplayName("AC-R1: 組織ADMINが別組織のnoShowIdを差し込むと400")
        void ac_r1_組織スコープの越境noShowIdは400() throws Exception {
            setAuth(adminCId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "ORGANIZATION", orgCId, noShowDId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode()));
        }

        /** AC-R2（ORGANIZATION 版）: 遮断時に別組織の record が書き換わっていない。 */
        @Test
        @DisplayName("AC-R2: 組織スコープの遮断時もrecordは不変")
        void ac_r2_組織スコープの遮断時にrecordは不変() throws Exception {
            setAuth(adminCId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "ORGANIZATION", orgCId, noShowDId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest());

            em.flush();
            em.clear();

            assertThat(noShowRepository.findById(noShowDId).orElseThrow().getDisputeResolution())
                    .as("越境の異議解決で orgD の NO_SHOW 記録が書き換えられてはならない")
                    .isNull();
        }

        /** AC-R3（ORGANIZATION 版）: 正当な組織 ADMIN の異議解決は 200（非回帰）。 */
        @Test
        @DisplayName("AC-R3: 正当な組織ADMINの異議解決は200（非回帰）")
        void ac_r3_組織ADMINの異議解決は200() throws Exception {
            setAuth(adminCId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "ORGANIZATION", orgCId, noShowCId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isOk());
        }

        /** AC-R1（スコープ種別越境）: 同じ scopeId でも scopeType が異なれば遮断されること。 */
        @Test
        @DisplayName("AC-R1: TEAMスコープのnoShowIdをORGANIZATION URLに差し込むと400")
        void ac_r1_スコープ種別違いは400() throws Exception {
            setAuth(adminCId);
            mockMvc.perform(patch("/api/v1/scopes/{scopeType}/{scopeId}/no-shows/{noShowId}/dispute",
                            "ORGANIZATION", orgCId, noShowAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resolveBody())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> resolveBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolution", DisputeResolution.REVOKED.name());
        body.put("adminNote", "裏目付テスト");
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 指定スコープに属する募集枠を 1 件作る（NO_SHOW 記録のスコープはこの募集枠経由で決まる）。 */
    private Long insertListing(RecruitmentScopeType scopeType, Long scopeId, Long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(1L)
                .title("RCRTAUTHZ 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(createdBy)
                .build()).getId();
    }

    /** 異議申立中（disputed=true・未解決）の NO_SHOW 記録を 1 件作る。 */
    private Long insertDisputedNoShow(Long listingId, Long userId) {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(listingId)
                .listingId(listingId)
                .userId(userId)
                .reason(NoShowReason.ADMIN_MARKED)
                .recordedBy(userId)
                .build();
        record.dispute();
        return noShowRepository.save(record).getId();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'RCRTAUTHZ', 'テスト', 'RCRTAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('rcrt-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('rcrto-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
