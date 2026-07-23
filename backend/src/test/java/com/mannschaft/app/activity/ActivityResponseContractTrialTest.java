package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活動記録 API のレスポンス契約テスト（試練 / テスト先行）。
 *
 * <p><b>背景</b>: {@code ActivityController} / {@code ActivityPublicController} は現在 JPA Entity
 * ({@link ActivityResultEntity}) をそのままレスポンスに直返ししている。後続 PR で DTO 化される予定であり、
 * 本テストは DTO 化後も守るべき「レスポンス JSON 契約」を実装より前に固定する（red 先行）。</p>
 *
 * <h2>固定する契約</h2>
 * <ul>
 *   <li><b>AC-3</b>: 代表 3 経路（GET 一覧・GET 単体・POST 作成）のレスポンス要素が、現行 JSON の
 *       フィールド名（id / scopeType / scopeId / templateId / title / activityDate / activityTimeStart /
 *       activityTimeEnd / location / venueId / description / fieldValues / attachments / visibility /
 *       status / scheduleId / createdBy / createdAt / updatedAt）を全て持ち、{@code fieldValues} は
 *       JSON 文字列型・{@code createdBy} は数値型であること。かつ {@code deletedAt} と {@code publishable}
 *       キーが<b>存在しない</b>こと（内部フィールド／派生ゲッターの漏洩を止める）。</li>
 *   <li><b>AC-4</b>: 一覧が該当 0 件のとき 200 かつ {@code data} が空配列。</li>
 *   <li><b>AC-11</b>: 認可回帰。認証必須 EP（{@code /api/v1/activities}）は未認証で 401。
 *       公開 EP（{@code /api/v1/public/...}）の未認証時ステータスを回帰ガードとして固定する。</li>
 * </ul>
 *
 * <p><b>現状の期待挙動</b>: {@code deletedAt}（null 値でも {@code spring.jackson.default-property-inclusion}
 * 未設定のため出力される）と、派生ゲッター {@code isPublishable()} 由来の {@code publishable} が現時点で JSON に
 * 出現するため、AC-3 の該当アサートは実装前は <b>red</b> になる。AC-4/AC-11 は現行挙動の回帰ガードであり green でよい。</p>
 *
 * <p>金型: {@code ResidentAuthzContractTest}（実 MySQL + 実 Security フィルタ + {@code @WithMockUser}）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("活動記録 API レスポンス契約テスト（試練）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityResponseContractTrialTest extends AbstractMySqlIntegrationTest {

    /** 認証ユーザー（teamA のメンバー）。@WithMockUser の username と一致させること。 */
    private static final long MEMBER_ID = 940100001L;
    private static final String MEMBER_ID_STR = "940100001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ActivityResultRepository resultRepository;

    @Autowired
    private ActivityTemplateRepository templateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long emptyTeamId;
    private Long templateId;
    private Long activityId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ACTIVITYRESP チームA");
        emptyTeamId = insertTeam("ACTIVITYRESP 空チーム");

        // 認証ユーザーを両チームのメンバーにする（checkMembership を通す）。
        MembershipTestHelper.insertMembership(em, MEMBER_ID, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, MEMBER_ID, ScopeType.TEAM, emptyTeamId, RoleKind.MEMBER);

        ActivityTemplateEntity template = templateRepository.save(ActivityTemplateEntity.builder()
                .scopeType(ActivityScopeType.TEAM)
                .scopeId(teamAId)
                .name("ACTIVITYRESP テンプレート")
                .createdBy(MEMBER_ID)
                .build());
        templateId = template.getId();

        // 全てのフィールドを非 null で埋めた活動記録を 1 件シードする
        // （null 落ちで「キーは実在するのに検出できない」偽緑を避ける）。
        ActivityResultEntity activity = resultRepository.save(ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.TEAM)
                .scopeId(teamAId)
                .templateId(templateId)
                .title("ACTIVITYRESP 練習記録")
                .activityDate(LocalDate.now())
                .activityTimeStart(LocalTime.of(10, 0))
                .activityTimeEnd(LocalTime.of(12, 0))
                .location("第一体育館")
                .venueId(555L)
                .description("紅白戦")
                .fieldValues("{\"score\":\"3-1\"}")
                .attachments("[]")
                .visibility(ActivityVisibility.PUBLIC)
                .status(ActivityStatus.PUBLISHED)
                .scheduleId(777L)
                .createdBy(MEMBER_ID)
                .build());
        activityId = activity.getId();

        em.flush();
        em.clear();
    }

    /** DTO 化後もレスポンスに存在すべきフィールド名。 */
    private static final String[] REQUIRED_FIELDS = {
            "id", "scopeType", "scopeId", "templateId", "title", "activityDate",
            "activityTimeStart", "activityTimeEnd", "location", "venueId", "description",
            "fieldValues", "attachments", "visibility", "status", "scheduleId",
            "createdBy", "createdAt", "updatedAt"
    };

    private void assertActivityContract(JsonNode node) {
        for (String field : REQUIRED_FIELDS) {
            assertThat(node.has(field))
                    .as("活動記録レスポンスに必須フィールド '%s' が存在する", field)
                    .isTrue();
        }
        // fieldValues は JSON 文字列型（オブジェクトに展開されていないこと）
        assertThat(node.get("fieldValues").isTextual())
                .as("fieldValues は JSON 文字列型である")
                .isTrue();
        // createdBy は数値型
        assertThat(node.get("createdBy").isNumber())
                .as("createdBy は数値型である")
                .isTrue();
        // 内部フィールド／派生ゲッターは漏洩しない
        assertThat(node.has("deletedAt"))
                .as("deletedAt キーはレスポンスから消える")
                .isFalse();
        assertThat(node.has("publishable"))
                .as("publishable キー（isPublishable 派生ゲッター）はレスポンスから消える")
                .isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-3: 代表 3 経路のレスポンス契約
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-3a: GET 単体のレスポンスがフィールド契約を満たし deletedAt/publishable を含まない")
    void ac3_getSingle_契約遵守() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activities/{id}", activityId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertActivityContract(data);
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-3b: GET 一覧の要素がフィールド契約を満たし deletedAt/publishable を含まない")
    void ac3_getList_契約遵守() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(teamAId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("data は配列である").isTrue();
        assertThat(data.size()).as("シードした活動記録が一覧に含まれる").isGreaterThanOrEqualTo(1);
        assertActivityContract(data.get(0));
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-3c: POST 作成のレスポンスがフィールド契約を満たし deletedAt/publishable を含まない")
    void ac3_postCreate_契約遵守() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("title", "ACTIVITYRESP 新規記録");
        body.put("activityDate", LocalDate.now().toString());
        body.put("activityTimeStart", "09:00:00");
        body.put("activityTimeEnd", "11:00:00");
        body.put("description", "新規作成テスト");
        Map<String, Object> fieldValues = new LinkedHashMap<>();
        fieldValues.put("note", "ok");
        body.put("fieldValues", fieldValues);
        body.put("visibility", "PUBLIC");

        MvcResult result = mockMvc.perform(post("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertActivityContract(data);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-4: 0 件一覧
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-4: 一覧が 0 件のとき 200 かつ data は空配列")
    void ac4_emptyList_空配列() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(emptyTeamId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("data は配列である").isTrue();
        assertThat(data.size()).as("0 件のとき空配列である").isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-11: 認可回帰
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11a: 認証必須 EP（GET /api/v1/activities）は未認証で 401")
    void ac11_activityListUnauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(teamAId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-11b: 認証済みユーザーは認証必須 EP に到達できる（401 ではない）")
    void ac11_activityListAuthenticated_not401() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(teamAId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-11c: 公開活動記録 EP（GET /api/v1/public/activities/{id}）の未認証時ステータス回帰ガード")
    void ac11_publicActivityUnauthenticated_regressionGuard() throws Exception {
        // NOTE: SecurityConfig（単一 SecurityFilterChain）には /api/v1/public/activities/* 系の
        // permitAll 登録が存在せず、.anyRequest().authenticated() のフォールバックで未認証は 401 になる。
        // 「公開 SSR 用」を標榜する ActivityPublicController が匿名到達できていない可能性がある
        // （殿への申し送り事項）。本テストは<現行挙動>を回帰ガードとして固定する。
        mockMvc.perform(get("/api/v1/public/activities/{id}", activityId))
                .andExpect(status().isUnauthorized());
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('a-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
