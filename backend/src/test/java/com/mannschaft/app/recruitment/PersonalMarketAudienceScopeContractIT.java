package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 個人市の札の公開先（{@code audienceScopes[].scopeId}）の所属検証を固定する契約テスト（CMP-260917-1135）。
 *
 * <p>対象: {@code PersonalMarketListingController#create} / {@code PersonalMarketListingController#update}。
 * どちらも本文でスコープIDを受け取るため自己スコープ（{@code @SelfScopedEndpoint}）の主張はできず、
 * {@code @AuthorizedInService} として認可の実体（{@code RecruitmentListingService#validatePersonalAudienceScopes}。
 * 公開先を本人の有効な所属と照合し、所属外は MARKET_008 で保存前に拒否する）を本テストで固定する。</p>
 *
 * <p>同じ URL・同じ本文で、認証主体だけを在籍メンバーと非メンバーで差し替えて結果が変わることを確かめる。
 * 金型は {@code RecruitmentScopeContractIT}（addFilters=false・実 MySQL・SecurityContextHolder へ userId を投入）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("個人市の札の公開先は在籍スコープだけを指定できる（契約）")
class PersonalMarketAudienceScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/me/market/listings";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecruitmentCategoryRepository categoryRepository;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long memberId;
    private Long outsiderId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("PMAUD チーム");
        memberId = insertUser("pmaud-member@example.com");
        outsiderId = insertUser("pmaud-outsider@example.com");
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                .code("PMAUD_TEST")
                .nameI18nKey("recruitment.category.pmaudTest")
                .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                .displayOrder(0)
                .isActive(true)
                .build()).getId();
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PersonalMarketListingController#create: 在籍しないチームを公開先にすると 400 で札は作られず、在籍メンバーは同じ本文で 201")
    void create_公開先は在籍チームだけ() throws Exception {
        String body = objectMapper.writeValueAsString(createBody(teamId));

        setAuth(outsiderId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(countPersonalListings(outsiderId)).isZero();

        setAuth(memberId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(countPersonalListings(memberId)).isEqualTo(1L);
        assertThat(countAudienceRows(memberId, "TEAM", teamId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PersonalMarketListingController#update: 自分の下書きでも在籍しないチームを公開先にすると 400 で公開先は変わらず、在籍チームなら 200")
    void update_公開先は在籍チームだけ() throws Exception {
        Long otherTeamId = insertTeam("PMAUD 他チーム");
        Long draftId = insertPersonalDraft(memberId);
        em.flush();
        em.clear();

        setAuth(memberId);
        mockMvc.perform(patch(BASE + "/" + draftId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody(otherTeamId))))
                .andExpect(status().isBadRequest());
        assertThat(countAudienceRows(memberId, "TEAM", otherTeamId)).isZero();

        mockMvc.perform(patch(BASE + "/" + draftId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody(teamId))))
                .andExpect(status().isOk());
        assertThat(countAudienceRows(memberId, "TEAM", teamId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("所属の根拠は user_roles だけでも有効（validatePersonalAudienceScopes は findActiveTeamIds の role ∪ membership を使う）: "
            + "チーム ADMIN ロールのみ（memberships なし）のユーザーは同チームを公開先にできる")
    void create_user_rolesのみの所属でも公開先にできる() throws Exception {
        Long roleOnlyId = insertUser("pmaud-roleonly@example.com");
        MembershipTestHelper.insertUserRole(em, roleOnlyId, "ADMIN", teamId, null);
        em.flush();

        setAuth(roleOnlyId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody(teamId))))
                .andExpect(status().isCreated());
        assertThat(countAudienceRows(roleOnlyId, "TEAM", teamId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("組織の公開先は findActiveOrganizationIds で照合する: 組織 membership があれば ORGANIZATION を公開先にでき、無い非メンバーは 400")
    void create_組織所属は組織を公開先にできる() throws Exception {
        // 公開先の行はクロスドメインFKを持たないため、組織 ID は他と衝突しない固定値で足りる（照合は所属表だけを見る）
        long organizationId = 987_654_321L;
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        em.flush();
        String body = objectMapper.writeValueAsString(createBody("ORGANIZATION", organizationId));

        setAuth(outsiderId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(countAudienceRows(outsiderId, "ORGANIZATION", organizationId)).isZero();

        setAuth(memberId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(countAudienceRows(memberId, "ORGANIZATION", organizationId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN でも非メンバーなら公開先にできない（照合は所属表のみで、プラットフォーム権限は所属の根拠にならない）")
    void create_SYSTEM_ADMINの非メンバーは公開先にできない() throws Exception {
        Long sysAdminId = insertUser("pmaud-sysadmin@example.com");
        MembershipTestHelper.insertUserRole(em, sysAdminId, "SYSTEM_ADMIN", null, null);
        em.flush();

        setAuth(sysAdminId);
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody(teamId))))
                .andExpect(status().isBadRequest());
        assertThat(countPersonalListings(sysAdminId)).isZero();
    }

    private Map<String, Object> createBody(Long audienceTeamId) {
        return createBody("TEAM", audienceTeamId);
    }

    private Map<String, Object> createBody(String audienceScopeType, Long audienceScopeId) {
        LocalDateTime start = LocalDateTime.now().plusDays(30).withNano(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("title", "PMAUD 個人札");
        body.put("participationType", "INDIVIDUAL");
        body.put("startAt", start);
        body.put("endAt", start.plusHours(2));
        body.put("applicationDeadline", start.minusDays(1));
        body.put("autoCancelAt", start.minusDays(2));
        body.put("capacity", 10);
        body.put("minCapacity", 1);
        body.put("paymentEnabled", false);
        body.put("visibility", "SELECTED_SCOPES");
        body.put("location", "PMAUD 会場");
        body.put("audienceScopes", List.of(Map.of("scopeType", audienceScopeType, "scopeId", audienceScopeId)));
        return body;
    }

    private Map<String, Object> updateBody(Long audienceTeamId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visibility", "SELECTED_SCOPES");
        body.put("audienceScopes", List.of(Map.of("scopeType", "TEAM", "scopeId", audienceTeamId)));
        return body;
    }

    private Long insertPersonalDraft(Long ownerId) {
        LocalDateTime start = LocalDateTime.now().plusDays(30).withNano(0);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.PERSONAL)
                .scopeId(ownerId)
                .categoryId(categoryId)
                .title("PMAUD 下書き")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .location("PMAUD 会場")
                .status(RecruitmentListingStatus.DRAFT)
                .createdBy(ownerId)
                .build()).getId();
    }

    private long countPersonalListings(Long ownerId) {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_listings WHERE scope_type = 'PERSONAL' AND scope_id = :uid")
                .setParameter("uid", ownerId).getSingleResult()).longValue();
    }

    private long countAudienceRows(Long ownerId, String audienceScopeType, Long audienceScopeId) {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM recruitment_listing_audience_scopes a "
                                + "JOIN recruitment_listings l ON l.id = a.listing_id "
                                + "WHERE l.scope_type = 'PERSONAL' AND l.scope_id = :uid "
                                + "AND a.scope_type = :st AND a.scope_id = :tid")
                .setParameter("uid", ownerId).setParameter("st", audienceScopeType).setParameter("tid", audienceScopeId)
                .getSingleResult()).longValue();
    }

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
                                + "VALUES (:email, 'PMAUD', 'テスト', 'PMAUD テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('pmaud-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }
}
