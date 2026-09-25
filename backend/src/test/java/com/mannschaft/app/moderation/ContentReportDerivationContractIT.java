package com.mannschaft.app.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * コンテンツ通報（{@code POST /api/v1/reports}）の宛先・作成者・控えの導出契約テスト
 * （CMP-260917-1135 陣2c）。
 *
 * <p>設計書 F10.1 §content_reports: {@code scope_type} / {@code scope_id} /
 * {@code target_user_id} / {@code content_snapshot} は通報作成時に BE が対象コンテンツから導出し、
 * リクエスト本文の値は使わない。</p>
 * <ul>
 *   <li>対象を閲覧できる利用者は成功（所属は要件にしない。公開中の募集・PUBLIC 投稿は非メンバーでも可）</li>
 *   <li>閲覧できない対象・存在しない対象は同一応答（404 MODERATION_005）</li>
 *   <li>スコープを導出できない種別（USER / SOCIAL_PROFILE）は 400（MODERATION_006）</li>
 * </ul>
 *
 * <p>保存値は DB を直接 SELECT して検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("コンテンツ通報の導出契約テスト（CMP-260917-1135）")
class ContentReportDerivationContractIT extends AbstractMySqlIntegrationTest {

    private static final Long AUTHOR_USER_ID = 9_312_001L;
    private static final Long MEMBER_USER_ID = 9_312_002L;
    private static final Long OUTSIDER_USER_ID = 9_312_003L;
    private static final Long LISTING_OWNER_USER_ID = 9_312_004L;
    private static final Long UNRELATED_USER_ID = 9_312_005L;
    private static final Long NONEXISTENT_ID = 987_654_321L;

    /** 本文に載せる、対象とは無関係な値。保存値に現れてはならない。 */
    private static final String BODY_SCOPE_TYPE = "ORGANIZATION";
    private static final Long BODY_SCOPE_ID = 424_242L;
    private static final String BODY_SNAPSHOT = "{\"content\":\"本文で申告した控え\"}";

    private static final String TEAM_POST_CONTENT = "チーム内の投稿本文";
    private static final String REPLY_CONTENT = "チーム内の返信本文";
    private static final String PUBLIC_POST_CONTENT = "全体公開の投稿本文";
    private static final String PUBLIC_LISTING_TITLE = "公開中の募集タイトル";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TimelinePostRepository timelinePostRepository;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long teamPostId;
    private Long replyId;
    private Long publicPostId;
    private Long publicListingId;
    private Long scopeOnlyListingId;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(AUTHOR_USER_ID, MEMBER_USER_ID, OUTSIDER_USER_ID,
                LISTING_OWNER_USER_ID, UNRELATED_USER_ID)) {
            MembershipTestHelper.insertActiveUser(em, id);
        }
        teamId = insertTeam("通報導出契約チーム");
        MembershipTestHelper.insertMembership(em, AUTHOR_USER_ID, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, MEMBER_USER_ID, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        teamPostId = timelinePostRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(teamId)
                .userId(AUTHOR_USER_ID)
                .content(TEAM_POST_CONTENT)
                .build()).getId();
        replyId = timelinePostRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.TEAM)
                .scopeId(teamId)
                .userId(AUTHOR_USER_ID)
                .parentId(teamPostId)
                .content(REPLY_CONTENT)
                .build()).getId();
        publicPostId = timelinePostRepository.save(TimelinePostEntity.builder()
                .scopeType(PostScopeType.PUBLIC)
                .scopeId(0L)
                .userId(AUTHOR_USER_ID)
                .content(PUBLIC_POST_CONTENT)
                .build()).getId();

        publicListingId = insertListing(PUBLIC_LISTING_TITLE, RecruitmentVisibility.PUBLIC);
        scopeOnlyListingId = insertListing("チーム内だけの募集", RecruitmentVisibility.SCOPE_ONLY);

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("TIMELINE_POST")
    class TimelinePost {

        @Test
        @DisplayName("本文に別の scope・targetUserId・snapshot を載せても、保存値は投稿由来になる")
        void 保存値は投稿由来() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            MvcResult result = perform(bodyWithDeclaredValues("TIMELINE_POST", teamPostId))
                    .andExpect(status().isCreated())
                    .andReturn();

            StoredReport stored = selectReport(readId(result));
            assertThat(stored.scopeType()).isEqualTo("TEAM");
            assertThat(stored.scopeId()).isEqualTo(teamId);
            assertThat(stored.targetUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(stored.snapshotContent()).isEqualTo(TEAM_POST_CONTENT);
        }

        @Test
        @DisplayName("全体公開の投稿は、チームに所属しない利用者でも通報でき、保存値は投稿由来")
        void 全体公開投稿は非メンバーでも201() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult result = perform(bodyWithDeclaredValues("TIMELINE_POST", publicPostId))
                    .andExpect(status().isCreated())
                    .andReturn();

            StoredReport stored = selectReport(readId(result));
            assertThat(stored.scopeType()).isEqualTo("PUBLIC");
            assertThat(stored.scopeId()).isZero();
            assertThat(stored.targetUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(stored.snapshotContent()).isEqualTo(PUBLIC_POST_CONTENT);
        }

        @Test
        @DisplayName("閲覧できないチーム投稿は、存在しない投稿と同一応答（404 MODERATION_005）で保存されない")
        void 閲覧できない投稿は不在と同一応答() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult invisible = perform(bodyWithDeclaredValues("TIMELINE_POST", teamPostId)).andReturn();
            MvcResult missing = perform(bodyWithDeclaredValues("TIMELINE_POST", NONEXISTENT_ID)).andReturn();

            assertSameRejection(invisible, missing, 404, "MODERATION_005");
            assertThat(countReports()).isZero();
        }

        @Test
        @DisplayName("同じ URL・同じ本文で認証主体だけ差し替えると結果が変わる（非メンバー404・メンバー201）")
        void 認証主体だけ差し替えると結果が変わる() throws Exception {
            Map<String, Object> sameBody = bodyWithDeclaredValues("TIMELINE_POST", teamPostId);

            setAuthentication(OUTSIDER_USER_ID);
            perform(sameBody).andExpect(status().isNotFound());

            setAuthentication(MEMBER_USER_ID);
            perform(sameBody).andExpect(status().isCreated());

            assertThat(countReports()).isEqualTo(1L);
        }

        @Test
        @DisplayName("返信を TIMELINE_POST として指すと、不在と同一応答（404 MODERATION_005）")
        void 返信をPOSTとして指すと404() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(bodyWithDeclaredValues("TIMELINE_POST", replyId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_005"));
            assertThat(countReports()).isZero();
        }

        @Test
        @DisplayName("自分の投稿は通報できない（400 MODERATION_003）")
        void 自分の投稿は400() throws Exception {
            setAuthentication(AUTHOR_USER_ID);
            perform(bodyWithDeclaredValues("TIMELINE_POST", teamPostId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_003"));
            assertThat(countReports()).isZero();
        }
    }

    @Nested
    @DisplayName("TIMELINE_COMMENT")
    class TimelineComment {

        @Test
        @DisplayName("本文に別の scope・targetUserId・snapshot を載せても、保存値は返信由来になる")
        void 保存値は返信由来() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            MvcResult result = perform(bodyWithDeclaredValues("TIMELINE_COMMENT", replyId))
                    .andExpect(status().isCreated())
                    .andReturn();

            StoredReport stored = selectReport(readId(result));
            assertThat(stored.scopeType()).isEqualTo("TEAM");
            assertThat(stored.scopeId()).isEqualTo(teamId);
            assertThat(stored.targetUserId()).isEqualTo(AUTHOR_USER_ID);
            assertThat(stored.snapshotContent()).isEqualTo(REPLY_CONTENT);
        }

        @Test
        @DisplayName("閲覧できない返信は、存在しない返信と同一応答（404 MODERATION_005）")
        void 閲覧できない返信は不在と同一応答() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult invisible = perform(bodyWithDeclaredValues("TIMELINE_COMMENT", replyId)).andReturn();
            MvcResult missing = perform(bodyWithDeclaredValues("TIMELINE_COMMENT", NONEXISTENT_ID)).andReturn();

            assertSameRejection(invisible, missing, 404, "MODERATION_005");
            assertThat(countReports()).isZero();
        }

        @Test
        @DisplayName("返信でない投稿を TIMELINE_COMMENT として指すと、不在と同一応答（404 MODERATION_005）")
        void 親投稿をCOMMENTとして指すと404() throws Exception {
            setAuthentication(MEMBER_USER_ID);
            perform(bodyWithDeclaredValues("TIMELINE_COMMENT", teamPostId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_005"));
            assertThat(countReports()).isZero();
        }
    }

    @Nested
    @DisplayName("RECRUITMENT_LISTING")
    class RecruitmentListing {

        @Test
        @DisplayName("公開中の募集は非メンバーでも通報でき、本文の値に関わらず保存値は募集由来になる")
        void 公開募集は非メンバーでも201で保存値は募集由来() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult result = perform(bodyWithDeclaredValues("RECRUITMENT_LISTING", publicListingId))
                    .andExpect(status().isCreated())
                    .andReturn();

            StoredReport stored = selectReport(readId(result));
            assertThat(stored.scopeType()).isEqualTo("TEAM");
            assertThat(stored.scopeId()).isEqualTo(teamId);
            assertThat(stored.targetUserId()).isEqualTo(LISTING_OWNER_USER_ID);
            assertThat(stored.snapshotTitle()).isEqualTo(PUBLIC_LISTING_TITLE);
        }

        @Test
        @DisplayName("閲覧できない募集は、存在しない募集と同一応答（404 MODERATION_005）")
        void 閲覧できない募集は不在と同一応答() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            MvcResult invisible = perform(bodyWithDeclaredValues("RECRUITMENT_LISTING", scopeOnlyListingId))
                    .andReturn();
            MvcResult missing = perform(bodyWithDeclaredValues("RECRUITMENT_LISTING", NONEXISTENT_ID))
                    .andReturn();

            assertSameRejection(invisible, missing, 404, "MODERATION_005");
            assertThat(countReports()).isZero();
        }
    }

    @Nested
    @DisplayName("導出できない種別")
    class Underivable {

        @Test
        @DisplayName("USER はスコープを導出できないため 400（MODERATION_006）で保存されない")
        void USERは400() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            perform(bodyWithDeclaredValues("USER", UNRELATED_USER_ID))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_006"));
            assertThat(countReports()).isZero();
        }

        @Test
        @DisplayName("SOCIAL_PROFILE はスコープを導出できないため 400（MODERATION_006）で保存されない")
        void SOCIAL_PROFILEは400() throws Exception {
            setAuthentication(OUTSIDER_USER_ID);
            perform(bodyWithDeclaredValues("SOCIAL_PROFILE", 1L))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_006"));
            assertThat(countReports()).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private record StoredReport(String scopeType, Long scopeId, Long targetUserId,
                                String snapshotContent, String snapshotTitle) { }

    private ResultActions perform(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 対象とは無関係な scope・targetUserId・snapshot を本文に載せたリクエスト。 */
    private Map<String, Object> bodyWithDeclaredValues(String targetType, Long targetId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("reason", "SPAM");
        body.put("description", "通報導出契約テスト");
        body.put("scopeType", BODY_SCOPE_TYPE);
        body.put("scopeId", BODY_SCOPE_ID);
        body.put("targetUserId", UNRELATED_USER_ID);
        body.put("contentSnapshot", BODY_SNAPSHOT);
        return body;
    }

    private void assertSameRejection(MvcResult a, MvcResult b, int expectedStatus, String expectedCode)
            throws Exception {
        assertThat(a.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(b.getResponse().getStatus()).isEqualTo(a.getResponse().getStatus());
        JsonNode ea = objectMapper.readTree(a.getResponse().getContentAsString()).path("error");
        JsonNode eb = objectMapper.readTree(b.getResponse().getContentAsString()).path("error");
        assertThat(ea.path("code").asText()).isEqualTo(expectedCode);
        assertThat(eb.path("code").asText()).isEqualTo(ea.path("code").asText());
        assertThat(eb.path("message").asText()).isEqualTo(ea.path("message").asText());
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private long readId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private StoredReport selectReport(long id) {
        em.flush();
        Object[] row = (Object[]) em.createNativeQuery(
                        "SELECT scope_type, scope_id, target_user_id, "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_snapshot, '$.content')), "
                                + "JSON_UNQUOTE(JSON_EXTRACT(content_snapshot, '$.title')) "
                                + "FROM content_reports WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        return new StoredReport(
                (String) row[0],
                row[1] == null ? null : ((Number) row[1]).longValue(),
                row[2] == null ? null : ((Number) row[2]).longValue(),
                (String) row[3],
                (String) row[4]);
    }

    private long countReports() {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM content_reports WHERE description = '通報導出契約テスト'")
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('rptdrv-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertListing(String title, RecruitmentVisibility visibility) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(1L)
                .title(title)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .visibility(visibility)
                .createdBy(LISTING_OWNER_USER_ID)
                .build()).getId();
    }
}
