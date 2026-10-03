package com.mannschaft.app.promotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.promotion.entity.CouponEntity;
import com.mannschaft.app.promotion.entity.PromotionEntity;
import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import com.mannschaft.app.promotion.repository.CouponRepository;
import com.mannschaft.app.promotion.repository.PromotionRepository;
import com.mannschaft.app.promotion.repository.SavedSegmentPresetRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2B — promotion ドメイン（プロモーション・クーポン・
 * セグメントプリセット）API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2B。
 * {@code PromotionService}/{@code CouponService}/{@code SegmentPresetService} は
 * {@code findOrThrow} が (id, scopeType, scopeId) の複合条件で絞り込むのみで、
 * 呼び出しユーザーの所属・権限検証が一切無かった（Controller/Service とも認可呼び出しゼロ）。
 * 他チーム/他組織になりすまして課金対象の配信（publish/approve）やクーポンの不正値引き・
 * 無効化（営業妨害）、配信セグメントプリセットの改ざんを発生させられる穴を閉じる。</p>
 *
 * <p>金型: {@code ServiceRecordScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + {@code MembershipTestHelper} 経由の memberships/user_roles seed）。Spring Security
 * フィルタは無効化するが、越境 403 は {@code AccessControlService.checkMembership}/
 * {@code checkAdminOrAbove} のアプリケーション層例外（{@code COMMON_002} → 403）として
 * 発生するためフィルタ無効でも検証できる。</p>
 *
 * <h3>検証観点（3象限 + BOLA）</h3>
 * <ul>
 *   <li>非メンバー（outsider） → 403</li>
 *   <li>別チームの ADMIN（越境） → 403</li>
 *   <li>非ADMINメンバー（変更系のみ） → 403</li>
 *   <li>正当な ADMIN → 200/201/204</li>
 *   <li>BOLA: 自チーム ADMIN が他チーム所有の id を自チームの URL で指定 → 404（存在秘匿。
 *       {@code findByIdAndScope} が (id, scopeType, scopeId) 複合条件のため path の scopeId を
 *       騙っても他チームのエンティティは絶対に返らない）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("promotion ドメイン（プロモーション・クーポン・セグメントプリセット）認可契約テスト（試練）")
class PromotionAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PromotionRepository promotionRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private SavedSegmentPresetRepository presetRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;   // TEAM A の ADMIN（正当）
    private Long adminBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long promotionDraftA;    // TEAM A・DRAFT（update/delete用）
    private Long promotionApprovedA; // TEAM A・APPROVED（publish/schedule/cancel用）
    private Long promotionPendingA;  // TEAM A・PENDING_APPROVAL（approve用）
    private Long promotionB;         // TEAM B 所属（BOLA検証用）

    private Long couponA; // TEAM A 所属
    private Long couponB; // TEAM B 所属（BOLA検証用）

    private Long presetA; // TEAM A 所属
    private Long presetB; // TEAM B 所属（BOLA検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("PROMOAUTHZ チームA");
        teamBId = insertTeam("PROMOAUTHZ チームB");

        adminAId = insertUser("promoauthz-admin-a@example.com");
        adminBId = insertUser("promoauthz-admin-b@example.com");
        memberAId = insertUser("promoauthz-member-a@example.com");
        outsiderId = insertUser("promoauthz-outsider@example.com");

        // ADMIN 判定（checkAdminOrAbove → resolveEffectiveRoleName）は user_roles を見るが、
        // 所属判定（checkMembership → isMember）は memberships テーブルのみを見る（別系統）。
        // ADMIN ユーザーにも memberships 行を張る（ServiceRecordScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどのチームにも所属させない。

        promotionDraftA = promotionRepository.save(PromotionEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).createdBy(adminAId)
                .title("PROMOAUTHZ 下書き").body("本文").build()).getId();

        PromotionEntity approved = PromotionEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).createdBy(adminAId)
                .title("PROMOAUTHZ 承認済").body("本文").build();
        approved.approve(adminAId);
        promotionApprovedA = promotionRepository.save(approved).getId();

        PromotionEntity pending = PromotionEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).createdBy(adminAId)
                .title("PROMOAUTHZ 承認待ち").body("本文").build();
        pending.submitForApproval();
        promotionPendingA = promotionRepository.save(pending).getId();

        promotionB = promotionRepository.save(PromotionEntity.builder()
                .scopeType("TEAM").scopeId(teamBId).createdBy(adminBId)
                .title("PROMOAUTHZ チームB所属").body("本文").build()).getId();

        couponA = couponRepository.save(CouponEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).createdBy(adminAId)
                .title("PROMOAUTHZ クーポンA").couponType("PERCENTAGE")
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(30))
                .build()).getId();

        couponB = couponRepository.save(CouponEntity.builder()
                .scopeType("TEAM").scopeId(teamBId).createdBy(adminBId)
                .title("PROMOAUTHZ クーポンB").couponType("PERCENTAGE")
                .validFrom(LocalDateTime.now().minusDays(1))
                .validUntil(LocalDateTime.now().plusDays(30))
                .build()).getId();

        presetA = presetRepository.save(SavedSegmentPresetEntity.builder()
                .scopeType("TEAM").scopeId(teamAId).name("PROMOAUTHZ プリセットA")
                .conditions("{}").createdBy(adminAId).build()).getId();

        presetB = presetRepository.save(SavedSegmentPresetEntity.builder()
                .scopeType("TEAM").scopeId(teamBId).name("PROMOAUTHZ プリセットB")
                .conditions("{}").createdBy(adminBId).build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. プロモーション一覧（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/promotions（一覧）")
    class ListPromotions {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. プロモーション作成（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/promotions（作成）")
    class CreatePromotion {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403（なりすまし課金対象配信の起票を封鎖）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAへなりすまし作成）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "新規プロモーション");
            body.put("body", "本文");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. プロモーション詳細（閲覧系・entity由来: checkMembership + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/promotions/{id}（詳細）")
    class GetPromotion {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのURLを叩く越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BOLA: 自チームADMINが他チーム所有のpromotionIdを自チームURLで指定→404（存在秘匿）")
        void BOLA_他チームのIDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionB))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. プロモーション更新（変更系・entity由来: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /teams/{teamId}/promotions/{id}（更新）")
    class UpdatePromotion {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BOLA: 自チームADMINが他チーム所有のpromotionIdを自チームURLで更新→404")
        void BOLA_他チームのIDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "更新後タイトル");
            body.put("body", "更新後本文");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. プロモーション削除（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /teams/{teamId}/promotions/{id}（削除）")
    class DeletePromotion {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/promotions/{id}", teamAId, promotionDraftA))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 即時配信（変更系・entity由来: checkAdminOrAbove）— 課金対象配信のなりすまし封鎖
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST /teams/{teamId}/promotions/{id}/publish（即時配信）")
    class PublishPromotion {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/publish", teamAId, promotionApprovedA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（他チームになりすまし配信）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/publish", teamAId, promotionApprovedA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/publish", teamAId, promotionApprovedA))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. 承認（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /teams/{teamId}/promotions/{id}/approve（承認）")
    class ApprovePromotion {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/approve", teamAId, promotionPendingA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/approve", teamAId, promotionPendingA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/{id}/approve", teamAId, promotionPendingA))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 配信対象見積（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. POST /teams/{teamId}/promotions/estimate-audience（配信対象見積）")
    class EstimateAudience {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/estimate-audience", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/promotions/estimate-audience", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. クーポン一覧・作成（checkMembership / checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET・POST /teams/{teamId}/coupons（一覧・作成）")
    class CouponListCreate {

        @Test
        @DisplayName("一覧: 非メンバーは403")
        void 一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/coupons", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一覧: 正当メンバーは200")
        void 一覧_正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/coupons", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("作成: 非ADMINメンバーは403")
        void 作成_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/coupons", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCouponBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 別scope ADMINは403")
        void 作成_別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/coupons", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCouponBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 正当ADMINは201")
        void 作成_正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/coupons", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCouponBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createCouponBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "新規クーポン");
            body.put("couponType", "PERCENTAGE");
            body.put("discountValue", 10);
            body.put("validFrom", LocalDateTime.now().minusDays(1).toString());
            body.put("validUntil", LocalDateTime.now().plusDays(30).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. クーポン更新・削除・切替（変更系・entity由来: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. PUT・DELETE・PATCH /teams/{teamId}/coupons/{id}（更新・削除・切替）")
    class CouponUpdateDeleteToggle {

        @Test
        @DisplayName("更新: 非ADMINメンバーは403（他組織クーポンの不正値引き改ざんを封鎖）")
        void 更新_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/coupons/{id}", teamAId, couponA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCouponBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 正当ADMINは200")
        void 更新_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/coupons/{id}", teamAId, couponA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCouponBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("更新BOLA: 自チームADMINが他チーム所有のcouponIdを自チームURLで指定→404")
        void 更新_BOLA_他チームのIDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/coupons/{id}", teamAId, couponB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCouponBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 非ADMINメンバーは403")
        void 削除_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/coupons/{id}", teamAId, couponA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 正当ADMINは204")
        void 削除_正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/coupons/{id}", teamAId, couponA))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("切替: 非ADMINメンバーは403（他組織クーポンの無効化＝営業妨害を封鎖）")
        void 切替_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/coupons/{id}/toggle", teamAId, couponA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("切替: 別scope ADMINは403")
        void 切替_別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/coupons/{id}/toggle", teamAId, couponA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("切替: 正当ADMINは200")
        void 切替_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/coupons/{id}/toggle", teamAId, couponA))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateCouponBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "更新後クーポン");
            body.put("couponType", "PERCENTAGE");
            body.put("discountValue", 20);
            body.put("validFrom", LocalDateTime.now().minusDays(1).toString());
            body.put("validUntil", LocalDateTime.now().plusDays(60).toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. セグメントプリセット一覧・作成（checkMembership / checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. GET・POST /teams/{teamId}/segment-presets（一覧・作成）")
    class PresetListCreate {

        @Test
        @DisplayName("一覧: 非メンバーは403")
        void 一覧_非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/segment-presets", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一覧: 正当メンバーは200")
        void 一覧_正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/segment-presets", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("作成: 非ADMINメンバーは403")
        void 作成_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/segment-presets", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPresetBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 別scope ADMINは403")
        void 作成_別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/segment-presets", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPresetBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成: 正当ADMINは201")
        void 作成_正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/segment-presets", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPresetBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createPresetBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "新規プリセット");
            body.put("conditions", "{}");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. セグメントプリセット更新・削除（変更系・entity由来: checkAdminOrAbove + BOLA）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. PUT・DELETE /teams/{teamId}/segment-presets/{id}（更新・削除）")
    class PresetUpdateDelete {

        @Test
        @DisplayName("更新: 非ADMINメンバーは403（他組織の配信セグメントプリセット改ざんを封鎖）")
        void 更新_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePresetBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("更新: 正当ADMINは200")
        void 更新_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePresetBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("更新BOLA: 自チームADMINが他チーム所有のpresetIdを自チームURLで指定→404")
        void 更新_BOLA_他チームのIDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePresetBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("削除: 非ADMINメンバーは403")
        void 削除_非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 別scope ADMINは403")
        void 削除_別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("削除: 正当ADMINは204")
        void 削除_正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetA))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("削除BOLA: 自チームADMINが他チーム所有のpresetIdを自チームURLで指定→404")
        void 削除_BOLA_他チームのIDは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/segment-presets/{id}", teamAId, presetB))
                    .andExpect(status().isNotFound());
        }

        private Map<String, Object> updatePresetBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後プリセット");
            body.put("conditions", "{}");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'PROMOAUTHZ', 'テスト', 'PROMOAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
