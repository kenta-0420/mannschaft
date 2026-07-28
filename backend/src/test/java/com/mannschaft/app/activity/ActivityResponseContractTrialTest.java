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
 *       公開 EP（{@code /api/v1/public/...}）は<b>未認証で到達できること</b>を固定する。</li>
 *   <li><b>AC-12</b>: {@code ActivityPublicController} の残り 4 EP（team/org 一覧・team/org 詳細）の
 *       回帰ガード。0 件一覧の形状、詳細の<b>公開 8 項目ホワイトリスト契約</b>
 *       （{@code assertPublicActivityContract} 経由）、および<b>未認証で到達できること</b>を固定する
 *       （可視性フィルタが {@code ContentVisibilityChecker} 経由の別経路であるため、
 *       AC-3/AC-11 だけでは検出できない回帰を捕捉する）。</li>
 * </ul>
 *
 * <h2>公開 EP の扱いに関する更新履歴（2026-07-28・F06.4 匿名公開安全化）</h2>
 * <p>本テストは当初、公開 EP について「{@code SecurityConfig} に permitAll 登録が無く未認証は 401」
 * という<b>当時の挙動</b>を回帰ガードとして固定し、「公開 SSR 用を標榜する
 * {@code ActivityPublicController} が匿名到達できていない」ことを<b>殿への申し送り事項</b>として
 * コメントに残していた。</p>
 * <p>その申し送りは F06.4 公開活動記録の匿名公開安全化戦役で<b>解決済み</b>である。
 * GET 5 本が permitAll となり、あわせて親スコープ公開性検証・DRAFT 除外・スコープ詐称拒否・
 * 403→404 正規化・公開専用 DTO 化が入った。これに伴い本テストは以下のとおり<b>削除ではなく追随</b>させた:</p>
 * <ul>
 *   <li>AC-11c / AC-12e〜h: 「未認証 401」の固定 →「<b>未認証で到達できること</b>」を守る向きへ反転
 *       （401 に戻る回帰＝公開ページが匿名で読めなくなる事故を検知する）</li>
 *   <li>AC-12c / AC-12d: {@code assertActivityContract}（{@code scopeType} 等を必須とする認証済み契約）
 *       → {@code assertPublicActivityContract}（御裁可済み 8 項目ホワイトリスト）へ差し替え</li>
 * </ul>
 * <p>認証必須 EP 側（AC-3a/b/c）の契約は<b>一切変更していない</b>。
 * 公開契約の正準は {@code ActivityPublicContractIT}（AC-8 / AC-9）であり、本テストはその回帰ガードの位置づけ。</p>
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

    // AC-12: 公開活動記録EP（team/org 一覧・詳細）の回帰ガード用シード。
    private Long orgId;
    private Long emptyOrgId;
    private Long orgActivityId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ACTIVITYRESP チームA");
        emptyTeamId = insertTeam("ACTIVITYRESP 空チーム");
        orgId = insertOrganization("ACTIVITYRESP 組織A");
        emptyOrgId = insertOrganization("ACTIVITYRESP 空組織");

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

        // AC-12: 組織スコープの公開活動記録を 1 件シードする（team 用テンプレートを流用。
        // template_id の FK は activity_templates の実存のみを要求し、scope 一致は強制しない）。
        ActivityResultEntity orgActivity = resultRepository.save(ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.ORGANIZATION)
                .scopeId(orgId)
                .templateId(templateId)
                .title("ACTIVITYRESP 組織活動記録")
                .activityDate(LocalDate.now())
                .activityTimeStart(LocalTime.of(13, 0))
                .activityTimeEnd(LocalTime.of(15, 0))
                .location("第二体育館")
                .venueId(556L)
                .description("組織合同練習")
                .fieldValues("{\"score\":\"2-2\"}")
                .attachments("[]")
                .visibility(ActivityVisibility.PUBLIC)
                .status(ActivityStatus.PUBLISHED)
                .scheduleId(778L)
                .createdBy(MEMBER_ID)
                .build());
        orgActivityId = orgActivity.getId();

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

    // ───────────────────────────────────────────────────────────────────────
    // 公開（匿名到達可能）EP 専用のフィールド契約
    //
    // 【重要】上の REQUIRED_FIELDS / assertActivityContract は<b>認証必須 EP</b>
    // （/api/v1/activities 系。AC-3a/b/c）の契約であり、そのまま維持する。
    // 公開 EP（/api/v1/public/... 系）は F06.4 匿名公開安全化により、認証済み DTO
    // （ActivityRecordResponse）ではなく公開専用 DTO（PublicActivityDetail /
    // PublicActivitySummary）を返すようになった。両者は別契約なので<b>ヘルパを分ける</b>。
    // 共通ヘルパを書き換えると認証必須 EP 側の契約まで緩んでしまうため、絶対に統合しないこと。
    // 公開契約の正準は ActivityPublicContractIT（AC-8 / AC-9）。
    // ───────────────────────────────────────────────────────────────────────

    /** 公開 EP のレスポンスに存在してよいトップレベルキー（御裁可済み 8 項目・完全一致）。 */
    private static final String[] PUBLIC_ALLOWED_FIELDS = {
            "id", "title", "activityDate", "activityTimeStart", "activityTimeEnd",
            "description", "scopeRef", "createdAt"
    };

    /** 公開 EP のレスポンスから除外必須のキー（1 つでも漏れたら失敗）。 */
    private static final String[] PUBLIC_FORBIDDEN_FIELDS = {
            "fieldValues", "attachments", "createdBy", "visibility", "status",
            "templateId", "venueId", "scheduleId", "scopeId", "updatedAt",
            "deletedAt", "publishable", "location"
    };

    private void assertPublicActivityContract(JsonNode node) {
        java.util.Set<String> actual = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);

        assertThat(actual)
                .as("公開 EP のキーは御裁可済みの 8 項目のみ（想定外のキーが増えたら失敗）")
                .containsExactlyInAnyOrder(PUBLIC_ALLOWED_FIELDS);

        for (String forbidden : PUBLIC_FORBIDDEN_FIELDS) {
            assertThat(node.has(forbidden))
                    .as("公開 EP に除外必須の項目 '%s' が漏れている", forbidden)
                    .isFalse();
        }

        // scopeId 生値は出さず、scopeRef（scopeType / scopeId / scopeName）経由でのみ露出する
        JsonNode scopeRef = node.get("scopeRef");
        assertThat(scopeRef != null).as("scopeRef が存在する").isTrue();
        java.util.Set<String> scopeRefKeys = new java.util.LinkedHashSet<>();
        scopeRef.fieldNames().forEachRemaining(scopeRefKeys::add);
        assertThat(scopeRefKeys)
                .as("scopeRef のキーは scopeType / scopeId / scopeName の 3 項目のみ")
                .containsExactlyInAnyOrder("scopeType", "scopeId", "scopeName");
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
    @DisplayName("AC-11c: 公開活動記録 EP（GET /api/v1/public/activities/{id}）は未認証で到達できる")
    void ac11_publicActivityUnauthenticated_匿名到達できる() throws Exception {
        // 【解決済み】かつて本テストは「permitAll 登録が無く未認証は 401」という<現行挙動>を
        // 回帰ガードとして固定し、「公開 SSR 用を標榜する ActivityPublicController が匿名到達できて
        // いない」ことを殿への申し送り事項としていた。
        // F06.4 公開活動記録の匿名公開安全化（本戦役）でその申し送りが解決され、SecurityConfig に
        // GET 5 本の permitAll が入った。よって本ガードは「未認証で 401 になること」ではなく
        // 「未認証で到達できること（＝公開 API の約束が守られていること）」を守る向きへ反転する。
        // 401 に戻る回帰＝公開ページが再び匿名で読めなくなる事故であり、本テストがそれを検知する。
        mockMvc.perform(get("/api/v1/public/activities/{id}", activityId))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-12: 公開活動記録EP（team/org 一覧・詳細）の回帰ガード（検分指摘対応）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-12a: team 公開一覧が該当 0 件のとき 200 かつ data は空配列")
    void ac12_teamPublicList_emptyList_空配列() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/teams/{teamId}/activities", emptyTeamId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("data は配列である").isTrue();
        assertThat(data.size()).as("0 件のとき空配列である").isZero();
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-12b: org 公開一覧が該当 0 件のとき 200 かつ data は空配列")
    void ac12_orgPublicList_emptyList_空配列() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/organizations/{orgId}/activities", emptyOrgId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("data は配列である").isTrue();
        assertThat(data.size()).as("0 件のとき空配列である").isZero();
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-12c: team 公開詳細のレスポンスが公開8項目ホワイトリスト契約を満たす")
    void ac12_teamPublicDetail_契約遵守() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/public/teams/{teamId}/activities/{id}", teamAId, activityId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertPublicActivityContract(data);
    }

    @Test
    @WithMockUser(username = MEMBER_ID_STR)
    @DisplayName("AC-12d: org 公開詳細のレスポンスが公開8項目ホワイトリスト契約を満たす")
    void ac12_orgPublicDetail_契約遵守() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/v1/public/organizations/{orgId}/activities/{id}", orgId, orgActivityId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertPublicActivityContract(data);
    }

    // AC-12e〜h も AC-11c と同じく「未認証 401 の固定」から「未認証で到達できること」へ反転済み。
    // 反転の経緯は AC-11c のコメントを参照（F06.4 匿名公開安全化により申し送りが解決）。
    // シードした team / org はいずれも visibility=PUBLIC・未 archive・未削除であり、
    // 配下の記録も visibility=PUBLIC かつ status=PUBLISHED のため 200 が正となる。

    @Test
    @DisplayName("AC-12e: team 公開一覧 EP は未認証で到達できる")
    void ac12_teamPublicListUnauthenticated_匿名到達できる() throws Exception {
        mockMvc.perform(get("/api/v1/public/teams/{teamId}/activities", teamAId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-12f: team 公開詳細 EP は未認証で到達できる")
    void ac12_teamPublicDetailUnauthenticated_匿名到達できる() throws Exception {
        mockMvc.perform(get("/api/v1/public/teams/{teamId}/activities/{id}", teamAId, activityId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-12g: org 公開一覧 EP は未認証で到達できる")
    void ac12_orgPublicListUnauthenticated_匿名到達できる() throws Exception {
        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/activities", orgId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-12h: org 公開詳細 EP は未認証で到達できる")
    void ac12_orgPublicDetailUnauthenticated_匿名到達できる() throws Exception {
        mockMvc.perform(get("/api/v1/public/organizations/{orgId}/activities/{id}", orgId, orgActivityId))
                .andExpect(status().isOk());
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('a-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
