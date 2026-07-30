package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公開活動記録 API（5 本）の <b>匿名公開 契約テスト</b>（試練 / red 先行）。
 *
 * <p>対象エンドポイント:</p>
 * <ol>
 *   <li>{@code GET /api/v1/public/activities/{id}}（ID 直引き）</li>
 *   <li>{@code GET /api/v1/public/teams/{teamId}/activities}（チーム一覧）</li>
 *   <li>{@code GET /api/v1/public/teams/{teamId}/activities/{id}}（チーム詳細）</li>
 *   <li>{@code GET /api/v1/public/organizations/{orgId}/activities}（組織一覧）</li>
 *   <li>{@code GET /api/v1/public/organizations/{orgId}/activities/{id}}（組織詳細）</li>
 * </ol>
 *
 * <p><b>本テストは実装前に書かれた red テストである。</b> 現状の実装には以下の欠陥があり、
 * permitAll した瞬間に一斉に起爆する。本テストはそれらを機械的に検出する:</p>
 * <ul>
 *   <li>{@code ActivityRecordResponse} が現行 JSON 形をそのまま写像しており、
 *       {@code createdBy} / {@code visibility} / {@code status} / {@code fieldValues}（生 JSON）/
 *       {@code attachments}（生 JSON）/ {@code templateId} / {@code venueId} / {@code scheduleId} /
 *       {@code scopeId} 生値 / {@code updatedAt} / {@code location} が残存している（AC-8 / AC-9）</li>
 *   <li>{@code ActivityResultService#findPublicActivityById} が
 *       {@code findByIdAndVisibility(id, PUBLIC)} のみで status 条件が無く、DRAFT が匿名公開されうる（AC-11）</li>
 *   <li>親スコープ（チーム / 組織）の公開性チェックが全経路に無い（AC-14 / AC-15）。
 *       他公開系（{@code PublicPostQueryService}）は必ず {@code findPublicTeamById} /
 *       {@code findPublicOrganizationById} を先に引くが activity には無い</li>
 *   <li>詳細 2 本がパス変数 {@code teamId} / {@code orgId} と Entity の
 *       {@code scopeType} / {@code scopeId} を照合しておらず、スコープ詐称が通る（AC-16 / AC-17）</li>
 *   <li>非公開記録に {@code VISIBILITY_001} → <b>403</b> を返しており存在オラクルになっている（AC-12 / AC-18）</li>
 *   <li>一覧の limit が無上限（AC-35）・{@code PageImpl} 総件数がページ内件数に化ける（AC-29a / AC-31 / AC-33）</li>
 *   <li>公開一覧が SQL で 1 ページ分を取ってからメモリ上で可視性フィルタしており、
 *       落ちた分が補充されない<b>ページング歯抜け</b>（AC-30 / AC-30b / AC-31b / AC-35）</li>
 * </ul>
 *
 * <p><b>ページング系 AC のフィクスチャ要件（必読）</b>: ページングを検査する AC は必ず
 * {@link #seedInterleaved} で (a) 公開 / (b) MEMBERS_ONLY / (c) PUBLIC+DRAFT /
 * (d) 論理削除 の 4 種を<b>交互に</b>投入し、{@link #assertHiddenRowsOnFirstPage} で
 * 「非公開行が先頭ページに食い込んでいること」を自己検査すること。
 * 旧 AC-25 は 101 件すべてを PUBLIC + PUBLISHED で投入していたため落ちる行が 1 件も無く、
 * ページング歯抜けを構造的に再現できないまま素通りしていた。</p>
 *
 * <p><b>御裁可済みの公開項目（AC-8 の正解）</b>: {@code id} / {@code title} / {@code activityDate} /
 * {@code activityTimeStart} / {@code activityTimeEnd} / {@code description} / {@code scopeRef} /
 * {@code createdAt} の <b>8 つのみ</b>。{@code scopeRef} は
 * {@code com.mannschaft.app.publicview.dto.PublicScopeRef} と同形
 * （{@code scopeType} / {@code scopeId} / {@code scopeName}）を期待する。</p>
 *
 * <p><b>金型</b>: {@code PublicFileLinkContractIT}（{@link AbstractMySqlIntegrationTest} 継承 +
 * {@code @AutoConfigureMockMvc}（{@code addFilters=false} を<b>付けない</b>＝実 Security フィルタ
 * チェーンを通す）+ {@code @EnabledIf(...isDockerAvailable)}）。SQL 数の計測は
 * {@code ScheduleListMyAttendanceStatusIT} の Hibernate {@link Statistics} パターンを踏襲。</p>
 *
 * <p><b>フィクスチャ方針</b>: {@code application-test.yml} は {@code ddl-auto=create} +
 * {@code flyway.enabled=false} のため Flyway シードが入らない。teams / organizations /
 * activity_results はすべて native SQL で手動 seed する。ポートは固定しない
 * （{@code @SpringBootTest} 既定 + Testcontainers 自動採番）。
 * <b>日時・時刻列は必ずパラメータ bind すること</b>（{@code hibernate.jdbc.time_zone: UTC} と
 * JVM の JST で 9 時間ずれるため。詳細は {@code insertActivity} の Javadoc）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("公開活動記録 匿名公開 契約テスト（試練・red 先行）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ActivityPublicContractIT extends AbstractMySqlIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 公開項目のホワイトリスト / ブラックリスト（AC-8 / AC-9 の単一真実源）
    // ═══════════════════════════════════════════════════════════════════════

    /** AC-8: レスポンス JSON に存在してよいトップレベルキー（これ以外が 1 つでも増えたら失敗）。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "id", "title", "activityDate", "activityTimeStart", "activityTimeEnd",
            "description", "scopeRef", "createdAt");

    /** AC-8: ネストした {@code scopeRef} に存在してよいキー。 */
    private static final Set<String> ALLOWED_SCOPE_REF_KEYS = Set.of(
            "scopeType", "scopeId", "scopeName");

    /** AC-9: 除外必須（1 つでも含まれたら失敗）。失敗時にどれが漏れたか分かるよう個別に検証する。 */
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "fieldValues", "attachments", "createdBy", "visibility", "status",
            "templateId", "venueId", "scheduleId", "scopeId", "updatedAt",
            "deletedAt", "publishable", "location");

    private static final String PUBLIC_ACTIVITY_BY_ID = "/api/v1/public/activities/{id}";
    private static final String TEAM_ACTIVITY_LIST = "/api/v1/public/teams/{teamId}/activities";
    private static final String TEAM_ACTIVITY_DETAIL = "/api/v1/public/teams/{teamId}/activities/{id}";
    private static final String ORG_ACTIVITY_LIST = "/api/v1/public/organizations/{orgId}/activities";
    private static final String ORG_ACTIVITY_DETAIL = "/api/v1/public/organizations/{orgId}/activities/{id}";

    /** 記録に埋め込む「漏れてはいけない値」。生値がレスポンスに出ていないかの二重確認に使う。 */
    private static final String SECRET_LOCATION = "秘匿すべき開催場所（漏洩したら失格）";
    private static final String SECRET_FIELD_VALUES = "{\"secret_note\": \"内部限定メモ・漏洩厳禁\"}";
    private static final String SECRET_ATTACHMENTS = "{\"file_ids\": [4242]}";
    private static final Long SECRET_CREATED_BY = 987654L;
    private static final Long SECRET_TEMPLATE_ID = 5150L;
    private static final Long SECRET_VENUE_ID = 6060L;
    private static final Long SECRET_SCHEDULE_ID = 7070L;

    private static final String ACTIVITY_DATE = "2026-05-01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** AC-29a / AC-31 / AC-31b / AC-33 は HTTP 契約に総件数が現れないため、Service の {@link Page} を実物のまま検証する。 */
    @Autowired
    private ActivityResultService activityResultService;

    /** AC-32（等価性番人）で SQL 述語側の集合 S を得る。フィクスチャ自己検査でも生の並び順を得るのに使う。 */
    @Autowired
    private ActivityResultRepository resultRepository;

    /** AC-32（等価性番人）で F00 側の集合 F を得る。 */
    @Autowired
    private ContentVisibilityChecker contentVisibilityChecker;

    @PersistenceContext
    private EntityManager em;

    // ── 親スコープ（teams / organizations）─────────────────────────────────
    private Long publicTeamId;
    private String publicTeamName;
    private Long otherPublicTeamId;
    private Long emptyPublicTeamId;
    private Long privateTeamId;
    private Long archivedTeamId;
    private Long suspendedTeamId;

    private Long publicOrgId;
    private Long privateOrgId;
    private Long archivedOrgId;
    private Long suspendedOrgId;

    // ── 活動記録 ───────────────────────────────────────────────────────────
    /** PUBLIC + PUBLISHED・全項目充填（AC-8 / AC-9 の主対象）。 */
    private Long publishedPublicActivityId;
    /** PUBLIC だが DRAFT（AC-11）。 */
    private Long draftPublicActivityId;
    /** MEMBERS_ONLY + PUBLISHED（AC-12 / AC-18）。 */
    private Long membersOnlyActivityId;
    /** PUBLIC + PUBLISHED だが論理削除済み（AC-13 / AC-18）。 */
    private Long deletedActivityId;
    /** activityTimeStart / activityTimeEnd が null（AC-23）。 */
    private Long nullTimesActivityId;
    /** description が null（AC-24）。 */
    private Long nullDescriptionActivityId;
    /** 別の PUBLIC チーム配下の PUBLIC 記録（AC-17）。 */
    private Long otherTeamActivityId;

    /** 非 PUBLIC / archived / 停止（論理削除）な親スコープ配下の PUBLIC 記録（AC-14 / AC-15）。 */
    private Long activityUnderPrivateTeamId;
    private Long activityUnderArchivedTeamId;
    private Long activityUnderSuspendedTeamId;
    private Long activityUnderPrivateOrgId;
    private Long activityUnderArchivedOrgId;
    private Long activityUnderSuspendedOrgId;

    /** PUBLIC 組織配下の PUBLIC 記録（組織側の正常系）。 */
    private Long orgPublicActivityId;

    /** 存在しない ID（AC-18）。 */
    private static final long NON_EXISTENT_ACTIVITY_ID = 999_999_999L;

    // ═══════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═══════════════════════════════════════════════════════════════════════

    @BeforeEach
    void setUp() {
        long nonce = System.nanoTime();

        publicTeamName = "公開チーム" + nonce;
        publicTeamId = insertTeam(publicTeamName, "act-pub-team-" + nonce, "PUBLIC", false, false);
        otherPublicTeamId = insertTeam("別の公開チーム", "act-other-team-" + nonce, "PUBLIC", false, false);
        emptyPublicTeamId = insertTeam("記録0件の公開チーム", "act-empty-team-" + nonce, "PUBLIC", false, false);
        // TeamEntity.Visibility は PUBLIC / GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE。
        // 「非 PUBLIC」の代表として MEMBERS_AND_ABOVE を使う。
        privateTeamId = insertTeam("非公開チーム", "act-priv-team-" + nonce, "MEMBERS_AND_ABOVE", false, false);
        archivedTeamId = insertTeam("凍結チーム", "act-arch-team-" + nonce, "PUBLIC", true, false);
        // teams / organizations に SUSPENDED 列は存在しない（OrganizationRepository §11.6 の
        // 「非アクティブ = deleted_at IS NOT NULL」が現行の唯一の停止表現）。よって停止相当＝論理削除で検証する。
        suspendedTeamId = insertTeam("停止チーム", "act-susp-team-" + nonce, "PUBLIC", false, true);

        publicOrgId = insertOrganization("公開組織" + nonce, "act-pub-org-" + nonce, "PUBLIC", false, false);
        privateOrgId = insertOrganization("非公開組織", "act-priv-org-" + nonce, "PRIVATE", false, false);
        archivedOrgId = insertOrganization("凍結組織", "act-arch-org-" + nonce, "PUBLIC", true, false);
        suspendedOrgId = insertOrganization("停止組織", "act-susp-org-" + nonce, "PUBLIC", false, true);

        publishedPublicActivityId = insertActivity(
                "TEAM", publicTeamId, "公開済み活動記録" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "12:00:00", "公開してよい説明文");
        draftPublicActivityId = insertActivity(
                "TEAM", publicTeamId, "下書きだがPUBLIC" + nonce, "PUBLIC", "DRAFT",
                false, "13:00:00", "14:00:00", "下書き（未公開）");
        membersOnlyActivityId = insertActivity(
                "TEAM", publicTeamId, "会員限定記録" + nonce, "MEMBERS_ONLY", "PUBLISHED",
                false, "15:00:00", "16:00:00", "会員限定");
        deletedActivityId = insertActivity(
                "TEAM", publicTeamId, "削除済み記録" + nonce, "PUBLIC", "PUBLISHED",
                true, "17:00:00", "18:00:00", "削除済み");
        nullTimesActivityId = insertActivity(
                "TEAM", publicTeamId, "時刻なし記録" + nonce, "PUBLIC", "PUBLISHED",
                false, null, null, "時刻未設定");
        nullDescriptionActivityId = insertActivity(
                "TEAM", publicTeamId, "説明なし記録" + nonce, "PUBLIC", "PUBLISHED",
                false, "09:00:00", "09:30:00", null);

        otherTeamActivityId = insertActivity(
                "TEAM", otherPublicTeamId, "別チームの記録" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "別チーム");

        activityUnderPrivateTeamId = insertActivity(
                "TEAM", privateTeamId, "非公開チーム配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が非公開");
        activityUnderArchivedTeamId = insertActivity(
                "TEAM", archivedTeamId, "凍結チーム配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が凍結");
        activityUnderSuspendedTeamId = insertActivity(
                "TEAM", suspendedTeamId, "停止チーム配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が停止");

        orgPublicActivityId = insertActivity(
                "ORGANIZATION", publicOrgId, "公開組織の記録" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "組織の公開記録");
        activityUnderPrivateOrgId = insertActivity(
                "ORGANIZATION", privateOrgId, "非公開組織配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が非公開");
        activityUnderArchivedOrgId = insertActivity(
                "ORGANIZATION", archivedOrgId, "凍結組織配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が凍結");
        activityUnderSuspendedOrgId = insertActivity(
                "ORGANIZATION", suspendedOrgId, "停止組織配下" + nonce, "PUBLIC", "PUBLISHED",
                false, "10:00:00", "11:00:00", "親が停止");

        em.flush();
        em.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［DTO 漏洩］AC-8 / AC-9
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-8) 単票レスポンス（詳細 3 経路）の JSON キー集合が、御裁可済みの 8 項目と
     * <b>完全一致</b>すること（ホワイトリスト方式・想定外のキーが 1 つでも増えたら失敗）。
     * ネストした {@code scopeRef} の中身も想定 3 キーのみであること。
     */
    @Test
    @DisplayName("(AC-8) 単票レスポンスのキーが許可8項目と完全一致（ホワイトリスト）")
    void ac8_単票レスポンスのキーが許可8項目のみ() throws Exception {
        assertWhitelistedKeys(
                getData(PUBLIC_ACTIVITY_BY_ID, publishedPublicActivityId),
                "ID 直引き詳細");
        assertWhitelistedKeys(
                getData(TEAM_ACTIVITY_DETAIL, publicTeamId, publishedPublicActivityId),
                "チーム詳細");
        assertWhitelistedKeys(
                getData(ORG_ACTIVITY_DETAIL, publicOrgId, orgPublicActivityId),
                "組織詳細");
    }

    /**
     * (AC-8) 一覧レスポンス（チーム / 組織）の各要素についても、キー集合が
     * 御裁可済みの 8 項目と完全一致すること。
     */
    @Test
    @DisplayName("(AC-8) 一覧レスポンスの各要素のキーが許可8項目と完全一致（ホワイトリスト）")
    void ac8_一覧レスポンスのキーが許可8項目のみ() throws Exception {
        JsonNode teamList = getData(TEAM_ACTIVITY_LIST, publicTeamId);
        assertThat(teamList.isArray()).as("チーム一覧は配列であること").isTrue();
        assertThat(teamList.size()).as("チーム一覧に公開記録が 1 件以上あること").isPositive();
        for (JsonNode item : teamList) {
            assertWhitelistedKeys(item, "チーム一覧要素");
        }

        JsonNode orgList = getData(ORG_ACTIVITY_LIST, publicOrgId);
        assertThat(orgList.isArray()).as("組織一覧は配列であること").isTrue();
        assertThat(orgList.size()).as("組織一覧に公開記録が 1 件以上あること").isPositive();
        for (JsonNode item : orgList) {
            assertWhitelistedKeys(item, "組織一覧要素");
        }
    }

    /**
     * (AC-8) 公開してよい 8 項目が実値で返ること（キーがあるだけでなく中身も契約どおり）。
     * {@code scopeRef} はチーム・組織名を含む参照オブジェクトであること。
     */
    @Test
    @DisplayName("(AC-8) 許可8項目が実値で返る（scopeRef はスコープ名を含む参照オブジェクト）")
    void ac8_許可項目が実値で返る() throws Exception {
        JsonNode data = getData(TEAM_ACTIVITY_DETAIL, publicTeamId, publishedPublicActivityId);

        assertThat(data.get("id").asLong()).isEqualTo(publishedPublicActivityId);
        assertThat(data.get("title").asText()).contains("公開済み活動記録");
        assertThat(data.get("activityDate").asText()).isEqualTo(ACTIVITY_DATE);
        assertThat(data.get("activityTimeStart").asText()).startsWith("10:00");
        assertThat(data.get("activityTimeEnd").asText()).startsWith("12:00");
        assertThat(data.get("description").asText()).isEqualTo("公開してよい説明文");
        assertThat(data.get("createdAt").isNull()).as("createdAt は非 null").isFalse();

        JsonNode scopeRef = data.get("scopeRef");
        assertThat(scopeRef != null).as("scopeRef が存在すること").isTrue();
        assertThat(scopeRef.get("scopeType").asText()).isEqualTo("TEAM");
        assertThat(scopeRef.get("scopeId").asLong()).isEqualTo(publicTeamId);
        assertThat(scopeRef.get("scopeName").asText())
                .as("scopeRef はチーム名を含むこと").isEqualTo(publicTeamName);
    }

    /**
     * (AC-9) 除外必須の項目が 1 つも含まれないこと。
     * どのフィールドが漏れたか失敗メッセージで判別できるよう、キーごとに個別 assert する。
     * 生値（location / fieldValues / attachments）がボディ文字列に現れないことも二重に確認する。
     */
    @Test
    @DisplayName("(AC-9) 除外必須項目が単票に1つも含まれない（キー個別検証）")
    void ac9_除外必須項目が単票に含まれない() throws Exception {
        MvcResult result = performOk(TEAM_ACTIVITY_DETAIL, publicTeamId, publishedPublicActivityId);
        String body = result.getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");

        assertForbiddenKeysAbsent(data, "チーム詳細");
        assertSecretValuesAbsent(body, "チーム詳細");
    }

    /**
     * (AC-9) 一覧の各要素にも除外必須項目が含まれないこと。
     */
    @Test
    @DisplayName("(AC-9) 除外必須項目が一覧要素に1つも含まれない（キー個別検証）")
    void ac9_除外必須項目が一覧要素に含まれない() throws Exception {
        MvcResult result = performOk(TEAM_ACTIVITY_LIST, publicTeamId);
        String body = result.getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(body).get("data");

        assertThat(list.size()).as("一覧に公開記録が 1 件以上あること").isPositive();
        for (JsonNode item : list) {
            assertForbiddenKeysAbsent(item, "チーム一覧要素 id=" + item.path("id").asText());
        }
        assertSecretValuesAbsent(body, "チーム一覧");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［可視性・状態］AC-11 〜 AC-15
    //
    // 【AC-36 回帰群】以下の AC-11（DRAFT 匿名）/ AC-12（MEMBERS_ONLY）/ AC-13（論理削除）/
    // AC-14・AC-15（親スコープ）/ AC-16・AC-17（スコープ詐称）は、公開一覧を SQL 述語へ
    // 載せ替えても<b>すべて 404 のまま</b>でなければならない。門2（SQL 述語）の導入で
    // 門1（親スコープ公開性）や門4（スコープ詐称拒否）が緩んでいないことを固定する回帰群である。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-11) visibility=PUBLIC でも status=DRAFT の記録は未認証で 404。
     *
     * <p>現状 {@code findPublicActivityById} は {@code findByIdAndVisibility(id, PUBLIC)} のみで
     * status 条件が無いため ID 直引きで下書きが匿名公開されうる。詳細 2 本は
     * {@code assertCanView} 経由で 403（存在オラクル）になる。いずれも 404 が正。</p>
     */
    @Test
    @DisplayName("(AC-11) visibility=PUBLIC でも status=DRAFT なら未認証は404")
    void ac11_PUBLICでもDRAFTなら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, draftPublicActivityId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, publicTeamId, draftPublicActivityId);

        JsonNode list = getData(TEAM_ACTIVITY_LIST, publicTeamId);
        assertThat(idsOf(list)).as("DRAFT は一覧にも現れない").doesNotContain(draftPublicActivityId);
    }

    /**
     * (AC-12) visibility=MEMBERS_ONLY は未認証で <b>404</b>（403 ではない）。
     *
     * <p>現状は {@code VisibilityErrorCode.VISIBILITY_001} → 403 を返しており、
     * 「存在するが権限がない」ことを漏らす存在オラクルになっている。他公開系と同じく
     * 存在秘匿のため一律 404 が正。</p>
     */
    @Test
    @DisplayName("(AC-12) visibility=MEMBERS_ONLY は未認証で404（403 ではない）")
    void ac12_MEMBERS_ONLYは404であり403ではない() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, membersOnlyActivityId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, publicTeamId, membersOnlyActivityId);

        JsonNode list = getData(TEAM_ACTIVITY_LIST, publicTeamId);
        assertThat(idsOf(list)).as("MEMBERS_ONLY は一覧にも現れない").doesNotContain(membersOnlyActivityId);
    }

    /**
     * (AC-13) 論理削除済み（{@code deleted_at} がセット済み）の記録は 404。
     */
    @Test
    @DisplayName("(AC-13) 論理削除済みの記録は404")
    void ac13_論理削除済みは404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, deletedActivityId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, publicTeamId, deletedActivityId);

        JsonNode list = getData(TEAM_ACTIVITY_LIST, publicTeamId);
        assertThat(idsOf(list)).as("論理削除済みは一覧にも現れない").doesNotContain(deletedActivityId);
    }

    /**
     * (AC-14) 親チームが非 PUBLIC（visibility != PUBLIC）なら、その配下の PUBLIC 記録も 404。
     *
     * <p>他公開系（{@code PublicPostQueryService}）は必ず {@code findPublicTeamById} を先に引くが、
     * activity には親スコープの公開性チェックが全経路に無い。F00 の親 ORG ガードも匿名では
     * snapshot が {@code empty()} で実質無効。</p>
     */
    @Test
    @DisplayName("(AC-14) 親チームが非PUBLICなら配下のPUBLIC記録も404（一覧も404）")
    void ac14_親チームが非PUBLICなら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderPrivateTeamId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, privateTeamId, activityUnderPrivateTeamId);
        expectNotFound(TEAM_ACTIVITY_LIST, privateTeamId);
    }

    /**
     * (AC-14) 親組織が非 PUBLIC（visibility=PRIVATE）なら、その配下の PUBLIC 記録も 404。
     */
    @Test
    @DisplayName("(AC-14) 親組織が非PUBLICなら配下のPUBLIC記録も404（一覧も404）")
    void ac14_親組織が非PUBLICなら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderPrivateOrgId);
        expectNotFound(ORG_ACTIVITY_DETAIL, privateOrgId, activityUnderPrivateOrgId);
        expectNotFound(ORG_ACTIVITY_LIST, privateOrgId);
    }

    /**
     * (AC-15) 親チームが archived（{@code archived_at} セット）でも 404。
     */
    @Test
    @DisplayName("(AC-15) 親チームがarchivedなら404（一覧も404）")
    void ac15_親チームがarchivedなら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderArchivedTeamId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, archivedTeamId, activityUnderArchivedTeamId);
        expectNotFound(TEAM_ACTIVITY_LIST, archivedTeamId);
    }

    /**
     * (AC-15) 親チームが停止（＝{@code deleted_at} セット。teams に SUSPENDED 列は無く、
     * 現行の「非アクティブ」定義は {@code deleted_at IS NOT NULL}）でも 404。
     */
    @Test
    @DisplayName("(AC-15) 親チームが停止（論理削除）なら404（一覧も404）")
    void ac15_親チームが停止なら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderSuspendedTeamId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, suspendedTeamId, activityUnderSuspendedTeamId);
        expectNotFound(TEAM_ACTIVITY_LIST, suspendedTeamId);
    }

    /**
     * (AC-15) 親組織が archived / 停止（論理削除）でも 404。
     */
    @Test
    @DisplayName("(AC-15) 親組織がarchived・停止なら404（一覧も404）")
    void ac15_親組織がarchivedまたは停止なら404() throws Exception {
        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderArchivedOrgId);
        expectNotFound(ORG_ACTIVITY_DETAIL, archivedOrgId, activityUnderArchivedOrgId);
        expectNotFound(ORG_ACTIVITY_LIST, archivedOrgId);

        expectNotFound(PUBLIC_ACTIVITY_BY_ID, activityUnderSuspendedOrgId);
        expectNotFound(ORG_ACTIVITY_DETAIL, suspendedOrgId, activityUnderSuspendedOrgId);
        expectNotFound(ORG_ACTIVITY_LIST, suspendedOrgId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［認可・IDOR］AC-16 〜 AC-18
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-16) TEAM スコープの記録を {@code /api/v1/public/organizations/{任意ID}/activities/{id}}
     * から取得しようとすると 404（スコープ詐称拒否）。
     *
     * <p>現状 {@code getOrgPublicActivity} は {@code assertCanView(id)} のみで、パス変数 {@code orgId} と
     * Entity の {@code scopeType} / {@code scopeId} を照合していないため詐称が通る。</p>
     */
    @Test
    @DisplayName("(AC-16) TEAMスコープの記録を組織パスから取得しようとすると404（スコープ詐称拒否）")
    void ac16_TEAMスコープ記録を組織パスから取得できない() throws Exception {
        // 実在する PUBLIC 組織の ID を使ってもなお 404（scopeType 不一致）。
        expectNotFound(ORG_ACTIVITY_DETAIL, publicOrgId, publishedPublicActivityId);
        // 逆方向（ORGANIZATION スコープの記録をチームパスから）も同様に 404。
        expectNotFound(TEAM_ACTIVITY_DETAIL, publicTeamId, orgPublicActivityId);
    }

    /**
     * (AC-17) 別チーム ID のパスから他チームの記録を取得しようとすると 404。
     */
    @Test
    @DisplayName("(AC-17) 別チームIDのパスから他チームの記録を取得しようとすると404")
    void ac17_別チームIDのパスから他チームの記録を取得できない() throws Exception {
        expectNotFound(TEAM_ACTIVITY_DETAIL, otherPublicTeamId, publishedPublicActivityId);
        expectNotFound(TEAM_ACTIVITY_DETAIL, publicTeamId, otherTeamActivityId);
    }

    /**
     * (AC-18) 存在しない ID・非公開 ID・削除済み ID で、<b>ステータスコードもレスポンスボディも
     * 区別できない</b>こと（列挙オラクル封じ）。ボディまで完全一致で比較する。
     *
     * <p>現状は非公開が 403（{@code VISIBILITY_001}）・存在しないものが 404 と分岐しており、
     * 攻撃者が「どの ID が実在するか」を列挙できる。</p>
     */
    @Test
    @DisplayName("(AC-18) 存在しない・非公開・削除済みでステータスもボディも区別できない（列挙オラクル封じ）")
    void ac18_存在しない_非公開_削除済みが区別できない() throws Exception {
        // ── チーム詳細経路 ──
        MvcResult missing = perform(TEAM_ACTIVITY_DETAIL, publicTeamId, NON_EXISTENT_ACTIVITY_ID);
        MvcResult hidden = perform(TEAM_ACTIVITY_DETAIL, publicTeamId, membersOnlyActivityId);
        MvcResult deleted = perform(TEAM_ACTIVITY_DETAIL, publicTeamId, deletedActivityId);

        assertThat(missing.getResponse().getStatus())
                .as("存在しない ID は 404").isEqualTo(404);
        assertThat(hidden.getResponse().getStatus())
                .as("非公開 ID も 404（403 で存在を漏らさない）").isEqualTo(404);
        assertThat(deleted.getResponse().getStatus())
                .as("削除済み ID も 404").isEqualTo(404);

        String missingBody = missing.getResponse().getContentAsString();
        assertThat(hidden.getResponse().getContentAsString())
                .as("非公開 ID のボディが存在しない ID と一致すること（列挙オラクル封じ）")
                .isEqualTo(missingBody);
        assertThat(deleted.getResponse().getContentAsString())
                .as("削除済み ID のボディが存在しない ID と一致すること（列挙オラクル封じ）")
                .isEqualTo(missingBody);

        // ── ID 直引き経路 ──
        MvcResult missingById = perform(PUBLIC_ACTIVITY_BY_ID, NON_EXISTENT_ACTIVITY_ID);
        MvcResult hiddenById = perform(PUBLIC_ACTIVITY_BY_ID, membersOnlyActivityId);
        MvcResult deletedById = perform(PUBLIC_ACTIVITY_BY_ID, deletedActivityId);

        assertThat(missingById.getResponse().getStatus()).as("ID 直引き: 存在しない → 404").isEqualTo(404);
        assertThat(hiddenById.getResponse().getStatus()).as("ID 直引き: 非公開 → 404").isEqualTo(404);
        assertThat(deletedById.getResponse().getStatus()).as("ID 直引き: 削除済み → 404").isEqualTo(404);

        String missingByIdBody = missingById.getResponse().getContentAsString();
        assertThat(hiddenById.getResponse().getContentAsString())
                .as("ID 直引き: 非公開のボディが存在しない ID と一致すること").isEqualTo(missingByIdBody);
        assertThat(deletedById.getResponse().getContentAsString())
                .as("ID 直引き: 削除済みのボディが存在しない ID と一致すること").isEqualTo(missingByIdBody);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［空・0件・null］AC-22 〜 AC-24
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-22) 公開記録が 0 件の（PUBLIC な）チームの一覧は 200 と空配列。500 や 404 にしない。
     */
    @Test
    @DisplayName("(AC-22) 公開記録0件のチーム一覧は200と空配列（500/404 にしない）")
    void ac22_公開記録0件のチーム一覧は200と空配列() throws Exception {
        JsonNode list = getData(TEAM_ACTIVITY_LIST, emptyPublicTeamId);
        assertThat(list.isArray()).as("配列であること").isTrue();
        assertThat(list.size()).as("空配列であること").isZero();
    }

    /**
     * (AC-23) {@code activityTimeStart} / {@code activityTimeEnd} が null の記録も 200 で返り、
     * 当該項目が <b>キーとして存在したうえで null</b> で出ること
     * （キーごと消えると FE の型契約が崩れるため NON_NULL 除去は不可）。
     */
    @Test
    @DisplayName("(AC-23) activityTimeStart/End が null の記録も200・当該項目は null で出る")
    void ac23_活動時刻nullでも200でnullが返る() throws Exception {
        JsonNode data = getData(TEAM_ACTIVITY_DETAIL, publicTeamId, nullTimesActivityId);

        assertThat(data.has("activityTimeStart")).as("activityTimeStart キーが存在すること").isTrue();
        assertThat(data.has("activityTimeEnd")).as("activityTimeEnd キーが存在すること").isTrue();
        assertThat(data.get("activityTimeStart").isNull()).as("activityTimeStart は null").isTrue();
        assertThat(data.get("activityTimeEnd").isNull()).as("activityTimeEnd は null").isTrue();

        assertWhitelistedKeys(data, "時刻 null の詳細");
    }

    /**
     * (AC-24) {@code description} が null の記録も 200 で返ること。
     */
    @Test
    @DisplayName("(AC-24) description が null の記録も200")
    void ac24_descriptionがnullでも200() throws Exception {
        JsonNode data = getData(TEAM_ACTIVITY_DETAIL, publicTeamId, nullDescriptionActivityId);

        assertThat(data.has("description")).as("description キーが存在すること").isTrue();
        assertThat(data.get("description").isNull()).as("description は null").isTrue();

        assertWhitelistedKeys(data, "description null の詳細");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［ページング歯抜け］AC-30 / AC-30b / AC-30c
    //
    // 【撤回した AC とその理由】
    //  ・AC-25 / AC-26（旧・境界値）→ AC-35 へ置換。101 件・30 件すべてを
    //    PUBLIC + PUBLISHED で投入していたため、メモリ側フィルタが 1 件も落とさず
    //    「歯抜け」が構造的に発生しないフィクスチャだった。limit 丸めしか検査できて
    //    おらず、欠陥1 を素通りさせた張本人。混在フィクスチャ版の AC-35 で置き換える。
    //  ・AC-28（旧・N+1）→ AC-34 へ置換。同じく全件公開フィクスチャだったため、
    //    可視性で落ちる行がある状態での SQL 数不変性を検査できていなかった。
    //  ・AC-29b（上界近似の許容）→ AC-31 へ置換。「総件数は上界近似でよい」という
    //    前提そのものが本 PR で失効する（SQL で絞るので実総数が出る）。
    //  ・AC-29c（1 ページ目に非公開 5 件 → hasSize(15)）→ 撤回。これは
    //    <b>旧バグ（歯抜け）の挙動を期待値として固定していた</b> AC である。
    //    根治後は「20 件要求したら公開 20 件が返る」が正しく、15 は誤り。
    //    テストを甘くしたのではなく、誤った期待値を正した。AC-30 が後継。
    //  ・AC-29a（全件公開で総件数=実総数）→ 維持。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-30) 公開 40 件・非公開 20 件が<b>交互に</b>並ぶスコープで {@code limit=20} を要求したとき、
     * <b>ちょうど 20 件</b>返り、全件が公開（(a)）であること。
     *
     * <p>旧実装は SQL で 1 ページ分（20 行）を取ってからメモリ上で可視性フィルタしていたため、
     * 落ちた分が補充されず 20 件要求しても 13 件程度しか返らない「ページング歯抜け」が起きていた。</p>
     */
    @Test
    @DisplayName("(AC-30) 公開40件・非公開20件の交互配置で limit=20 はちょうど20件返る（歯抜け禁止）")
    void ac30_混在でもlimit20はちょうど20件返る() throws Exception {
        Long teamId = insertTeam("歯抜け検査チーム", "act-gap30-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "GAP30", 40, 10, 5, 5);
        assertHiddenRowsOnFirstPage(teamId, 20, Set.copyOf(publicIds));

        List<Long> ids = listIds(teamId, "20");

        assertThat(ids).as("limit=20 でちょうど 20 件返ること（歯抜けを許さない）").hasSize(20);
        assertThat(ids).as("返却された全件が公開(a)であること").isSubsetOf(publicIds);
        assertThat(ids).as("id の重複が無いこと").doesNotHaveDuplicates();
    }

    /**
     * (AC-30b) 公開が要求件数に満たないときは、非公開を混ぜて<b>詰めない</b>こと。
     * 公開 7 件・非公開 30 件で {@code limit=20} なら 7 件ちょうど。
     */
    @Test
    @DisplayName("(AC-30b) 公開7件・非公開30件で limit=20 は7件ちょうど（詰めすぎない）")
    void ac30b_公開が足りないときは詰めない() throws Exception {
        Long teamId = insertTeam("公開僅少チーム", "act-gap30b-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "GAP30B", 7, 15, 10, 5);
        assertHiddenRowsOnFirstPage(teamId, 20, Set.copyOf(publicIds));

        List<Long> ids = listIds(teamId, "20");

        assertThat(ids).as("公開 7 件しか無いので 7 件ちょうど").hasSize(7);
        assertThat(ids).as("返却された全件が公開(a)であること").containsExactlyInAnyOrderElementsOf(publicIds);
    }

    /**
     * (AC-30c) 公開 0 件・非公開 30 件でも 200 と空配列（404 / 500 にしない）。
     */
    @Test
    @DisplayName("(AC-30c) 公開0件・非公開30件でも200と空配列（404/500 にしない）")
    void ac30c_公開0件でも200と空配列() throws Exception {
        Long teamId = insertTeam("公開ゼロチーム", "act-gap30c-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "GAP30C", 0, 15, 10, 5);
        assertThat(publicIds).as("フィクスチャ要件: 公開行は 0 件").isEmpty();

        JsonNode list = getData(TEAM_ACTIVITY_LIST, teamId);
        assertThat(list.isArray()).as("配列であること").isTrue();
        assertThat(list.size()).as("空配列であること").isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［総件数・ページ通し］AC-31 / AC-31b
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-31) 混在フィクスチャでも総件数が<b>実公開件数と厳密に一致</b>すること。
     *
     * <p>旧実装の総件数は「DB の全行数 − このページで落ちた件数」という<b>上界近似</b>だった
     * （旧 AC-29b が {@code isBetween(50, 55)} でその幅を許容していた）。SQL 述語で絞る本実装では
     * 近似ではなく実数が出るため、厳密一致で固定する。</p>
     */
    @Test
    @DisplayName("(AC-31) 混在フィクスチャで総件数が実公開件数40と厳密一致・ページ内件数20に化けない")
    void ac31_総件数が実公開件数と厳密一致する() {
        Long teamId = insertTeam("総件数厳密チーム", "act-total31-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "TOTAL31", 40, 10, 5, 5);
        assertHiddenRowsOnFirstPage(teamId, 20, Set.copyOf(publicIds));

        Page<ActivityResultEntity> page = activityResultService.listPublicActivities(
                ActivityScopeType.TEAM, teamId, PageRequest.of(0, 20));

        assertThat(page.getTotalElements())
                .as("旧バグ禁止: 総件数がページ内件数(20)に化けていないこと")
                .isNotEqualTo(20L);
        assertThat(page.getTotalElements())
                .as("総件数は実公開件数 40 と厳密一致（上界近似 55 でも過小 20 でもない）")
                .isEqualTo(40L);
        assertThat(page.getTotalPages()).as("総ページ数は 2（40 / 20）").isEqualTo(2);
        assertThat(page.getContent()).as("1 ページ目は 20 件").hasSize(20);
    }

    /**
     * (AC-31b) page 0 / 1 / 2 を通しで取得したとき、
     * <b>重複なし</b>（ページ間で id 集合が交わらない）かつ<b>取りこぼしなし</b>
     * （page0 ∪ page1 が公開 40 件と完全一致）であること。
     *
     * <p>歯抜けは「1 ページ内の件数不足」だけでなく「ページ境界で行が消える／二重に出る」形でも
     * 現れる。件数だけでなく id 集合そのものを突き合わせて固定する。</p>
     */
    @Test
    @DisplayName("(AC-31b) page0/1/2 通しで重複なし・取りこぼしなし（id集合が公開40件と完全一致）")
    void ac31b_ページ通しで重複も取りこぼしも無い() {
        Long teamId = insertTeam("ページ通しチーム", "act-total31b-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "TOTAL31B", 40, 10, 5, 5);
        assertHiddenRowsOnFirstPage(teamId, 20, Set.copyOf(publicIds));

        List<Long> page0 = pageIds(teamId, 0, 20);
        List<Long> page1 = pageIds(teamId, 1, 20);
        List<Long> page2 = pageIds(teamId, 2, 20);

        assertThat(page0).as("page0 は 20 件").hasSize(20);
        assertThat(page1).as("page1 は 20 件").hasSize(20);
        assertThat(page2).as("page2 は 0 件").isEmpty();

        assertThat(page0).as("page0 と page1 の id 集合が交わらないこと（重複なし）")
                .doesNotContainAnyElementsOf(page1);

        List<Long> union = new ArrayList<>(page0);
        union.addAll(page1);
        assertThat(union)
                .as("page0 ∪ page1 が公開 40 件の id 集合と完全一致すること（取りこぼしなし）")
                .containsExactlyInAnyOrderElementsOf(publicIds);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［等価性番人］AC-32
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-32) <b>本 PR の安全性の全体を担う番人</b>。
     * 公開一覧の SQL 述語（門2）と F00 可視性 Resolver（門3）が、匿名（{@code userId=null}）に対して
     * <b>厳密に同じ集合</b>を返すことを機械的に固定する。
     *
     * <h3>なぜこのテストが本案の安全性そのものなのか</h3>
     * <p>本 PR は「公開一覧を SQL 述語（{@code visibility=PUBLIC AND status=PUBLISHED}）で絞り、
     * F00 では二重確認するだけ」という形に変える。これは
     * <b>可視性判定器を 2 つ作ることではない</b> — 匿名のとき F00 のラダーは
     * {@code MembershipBatchQueryService#snapshotForUser}(userId=null) が
     * {@code UserScopeRoleSnapshot.empty()} を返すことで縮退し、
     * {@code StandardVisibility.PUBLIC} の Javadoc が
     * 「未認証時は PUBLIC かつ PUBLISHED のときのみ true、それ以外はすべて fail-closed」と
     * 明文で宣言している。つまり SQL 述語は<b>F00 自身の宣言の機械的な転写</b>である。</p>
     *
     * <p>したがって守るべき不変条件はただ一つ、<b>「S == F」</b>である。
     * 片方だけが変更されたら（SQL に条件を足す／F00 のマッピングを変える／
     * {@code ActivityVisibilityMapper} を書き換える）このテストが必ず落ちる。
     * 将来 {@link ActivityVisibility} に第 3 の値が追加された場合も、
     * 網羅組合せの追加とともに最初にここが落ち、設計者に「その値は匿名に見せるのか」の
     * 判断を強制する。<b>このテストを削除・緩和することは、本 PR の安全性の根拠を捨てることに等しい。</b></p>
     *
     * <p>組合せ: {@code visibility}(PUBLIC / MEMBERS_ONLY) × {@code status}(PUBLISHED / DRAFT)
     * × {@code deleted}(false / true) の 8 通りを各 3 件・計 24 行。
     * 通過すべきは {@code PUBLIC × PUBLISHED × 未削除} の 3 件のみ。</p>
     */
    @Test
    @DisplayName("(AC-32) SQL述語の集合SとF00のfilterAccessibleの集合Fが厳密一致（等価性番人）")
    void ac32_SQL述語とF00の判定集合が厳密一致する() {
        Long teamId = insertTeam("等価性番人チーム", "act-equiv32-" + System.nanoTime(),
                "PUBLIC", false, false);
        long nonce = System.nanoTime();

        List<Long> allIds = new ArrayList<>();
        List<Long> expectedVisibleIds = new ArrayList<>();
        for (String visibility : List.of("PUBLIC", "MEMBERS_ONLY")) {
            for (String status : List.of("PUBLISHED", "DRAFT")) {
                for (boolean deleted : List.of(false, true)) {
                    for (int i = 0; i < 3; i++) {
                        Long id = insertActivity("TEAM", teamId,
                                "EQ32-" + visibility + "-" + status + "-" + deleted
                                        + "-" + nonce + "-" + i,
                                visibility, status, deleted, "10:00:00", "11:00:00", "等価性番人");
                        allIds.add(id);
                        if ("PUBLIC".equals(visibility) && "PUBLISHED".equals(status) && !deleted) {
                            expectedVisibleIds.add(id);
                        }
                    }
                }
            }
        }
        em.flush();
        em.clear();

        assertThat(allIds).as("フィクスチャ要件: 8 組合せ × 3 件 = 24 行").hasSize(24);
        assertThat(expectedVisibleIds).as("フィクスチャ要件: 通過すべきは (a) の 3 件のみ").hasSize(3);

        // S: SQL 述語（門2）が返す集合
        List<Long> sqlIds = resultRepository
                .findPublicByScopeTypeAndScopeId(
                        ActivityScopeType.TEAM, teamId, PageRequest.of(0, 100))
                .getContent().stream().map(ActivityResultEntity::getId).toList();

        // F: F00 ContentVisibilityChecker（門3）が匿名に対して返す集合
        Set<Long> f00Ids = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT, Set.copyOf(allIds), null);

        assertThat(sqlIds)
                .as("S（SQL 述語）は PUBLIC × PUBLISHED × 未削除 の 3 件のみであること")
                .containsExactlyInAnyOrderElementsOf(expectedVisibleIds);
        assertThat(sqlIds)
                .as("S == F: SQL 述語（門2）と F00 可視性（門3）の判定が匿名に対して厳密一致すること。"
                        + "片方だけが変わったらここが落ちる（本 PR の安全性の全体）")
                .containsExactlyInAnyOrderElementsOf(f00Ids);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［欠陥2: 認証済み経路の総件数］AC-33
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-33) 認証済み一覧 {@code listActivities} の総件数がページ内件数に化けないこと。
     *
     * <p>旧実装は {@code new PageImpl<>(filtered, pageable, filtered.size())} としており、
     * 55 件あってもページャが「20 件・1 ページ」に見え 2 ページ目へ辿り着けなかった。
     * 是正後は {@code TournamentService} と同じ流儀
     * （DB 総件数 − このページで落ちた件数）で算出する。</p>
     *
     * <p><b>認証済み経路の歯抜けは本 PR の対象外</b>（後続戦役）。認証済みでは F00 のラダーが
     * 縮退しないため SQL 化すると本物の独自述語になってしまう。よって総件数は
     * 「実公開件数 ≦ 総件数 ≦ 全行数」の上界近似のままであり、本 AC もその幅
     * （50 以上 55 以下）で固定する。旧バグ（20 への化け）だけは明示的に禁止する。</p>
     */
    @Test
    @DisplayName("(AC-33) 認証済み一覧の総件数がページ内件数20に化けない（50以上55以下・他人のDRAFTは非表示）")
    void ac33_認証済み一覧の総件数がページ内件数に化けない() {
        Long teamId = insertTeam("認証済み総件数チーム", "act-total33-" + System.nanoTime(),
                "PUBLIC", false, false);
        Long viewerUserId = 555_001L;
        insertMembership(viewerUserId, "TEAM", teamId);

        long nonce = System.nanoTime();
        List<Long> othersDraftIds = new ArrayList<>();
        // 公開 50 件 + 他人（created_by = SECRET_CREATED_BY）が作成した DRAFT 5 件を交互に投入
        for (int i = 0; i < 50; i++) {
            insertActivity("TEAM", teamId, "AC33-public-" + nonce + "-" + i,
                    "PUBLIC", "PUBLISHED", false, "10:00:00", "11:00:00", "公開");
            if (i % 10 == 0) {
                othersDraftIds.add(insertActivity("TEAM", teamId, "AC33-others-draft-" + nonce + "-" + i,
                        "PUBLIC", "DRAFT", false, "10:00:00", "11:00:00", "他人の下書き"));
            }
        }
        em.flush();
        em.clear();

        assertThat(othersDraftIds).as("フィクスチャ要件: 他人の DRAFT が 5 件").hasSize(5);
        assertThat(viewerUserId).as("フィクスチャ要件: 閲覧者は DRAFT の作成者ではないこと")
                .isNotEqualTo(SECRET_CREATED_BY);

        Page<ActivityResultEntity> page = activityResultService.listActivities(
                viewerUserId, ActivityScopeType.TEAM, teamId, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements())
                .as("旧バグ禁止: 総件数がページ内件数(20)に化けていないこと")
                .isNotEqualTo(20L);
        assertThat(page.getTotalElements())
                .as("総件数は実公開件数(50) 以上・全行数(55) 以下であること（認証済み経路は上界近似のまま）")
                .isBetween(50L, 55L);
        assertThat(page.getContent().stream().map(ActivityResultEntity::getId).toList())
                .as("他人が作成した DRAFT が 1 件も含まれないこと")
                .doesNotContainAnyElementsOf(othersDraftIds);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ［性能］AC-34 / AC-29a
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * (AC-34) 一覧取得が N+1 にならないこと（<b>混在フィクスチャ版</b>。旧 AC-28 の後継）。
     *
     * <p>Hibernate {@link Statistics#getPrepareStatementCount()} で JDBC ステートメント数を計測し、
     * <b>件数を変えてもクエリ数が一定</b>であることを検証する（絶対数ではなく不変性で見るため、
     * 実装が増える将来の SQL 追加に強い）。親スコープ（team/org）の公開性や F00 の可視性判定を
     * 記録ごとに引くような実装を入れると即座に破綻する。</p>
     *
     * <p>旧 AC-28 は全件公開のフィクスチャだったため「可視性で落ちる行がある状態」の
     * SQL 数を検査できていなかった。公開 3 + 非公開 3 / 公開 30 + 非公開 30 の混在で計測する。</p>
     */
    @Test
    @DisplayName("(AC-34) 混在フィクスチャでも一覧がN+1にならない（件数を変えてもクエリ数一定）")
    void ac34_混在でも一覧がN1にならない() throws Exception {
        Long smallTeamId = insertTeam("N1小チーム", "act-n1s-team-" + System.nanoTime(),
                "PUBLIC", false, false);
        Long largeTeamId = insertTeam("N1大チーム", "act-n1l-team-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> smallPublicIds = seedInterleaved(smallTeamId, "N1SMALL", 3, 1, 1, 1);
        List<Long> largePublicIds = seedInterleaved(largeTeamId, "N1LARGE", 30, 10, 10, 10);
        assertHiddenRowsOnFirstPage(smallTeamId, 50, Set.copyOf(smallPublicIds));
        assertHiddenRowsOnFirstPage(largeTeamId, 50, Set.copyOf(largePublicIds));
        em.flush();
        em.clear();

        Statistics stats = statisticsCleared();
        assertThat(listSize(smallTeamId, "50")).as("小チームは公開 3 件返る").isEqualTo(3);
        long smallStatements = stats.getPrepareStatementCount();

        em.clear();
        stats.clear();
        assertThat(listSize(largeTeamId, "50")).as("大チームは公開 30 件返る").isEqualTo(30);
        long largeStatements = stats.getPrepareStatementCount();

        assertThat(largeStatements)
                .as("公開 3 + 非公開 3（%d 本）と 公開 30 + 非公開 30（%d 本）で発行 SQL 数が"
                        + "一定であること（N+1 禁止）", smallStatements, largeStatements)
                .isEqualTo(smallStatements);
    }

    /**
     * (AC-35) {@code limit} の丸めが混在フィクスチャでも正しく、<b>返却全件が公開(a)</b>であること
     * （旧 AC-25 / AC-26 の後継）。
     *
     * <p>旧 AC-25 / AC-26 は投入した行がすべて PUBLIC + PUBLISHED だったため、
     * メモリ側フィルタが 1 件も落とさず「歯抜け」が構造的に起きないフィクスチャだった。
     * これが欠陥1（ページング歯抜け）を素通りさせた直接の原因である。
     * 公開 120 件・非公開 40 件の交互配置で再検査する。</p>
     */
    @Test
    @DisplayName("(AC-35) 混在フィクスチャで limit 丸め（100/101/0/-1）が正しく全件が公開")
    void ac35_混在でのlimit境界値() throws Exception {
        Long teamId = insertTeam("境界値混在チーム", "act-bound35-" + System.nanoTime(),
                "PUBLIC", false, false);
        List<Long> publicIds = seedInterleaved(teamId, "BOUND35", 120, 20, 10, 10);
        assertHiddenRowsOnFirstPage(teamId, 20, Set.copyOf(publicIds));

        assertLimitReturnsOnlyPublic(teamId, "100", 100, publicIds);
        assertLimitReturnsOnlyPublic(teamId, "101", 100, publicIds);
        assertLimitReturnsOnlyPublic(teamId, "100000", 100, publicIds);
        assertLimitReturnsOnlyPublic(teamId, "0", 20, publicIds);
        assertLimitReturnsOnlyPublic(teamId, "-1", 20, publicIds);
        assertLimitReturnsOnlyPublic(teamId, "-100", 20, publicIds);
    }

    /**
     * (AC-29a) 一覧の総件数が正しいこと（<b>全件公開</b>のケース）。ページサイズより多い公開記録が
     * あるとき、総件数がページ内件数ではなく<b>実総数</b>になること。
     *
     * <p>旧実装 {@code ActivityResultService#listPublicActivities} は
     * {@code new PageImpl<>(filtered, pageable, filtered.size())} としており、総件数が
     * ページ内件数に化けていた（ページャが 1 ページしか無いように見え、2 ページ目以降へ辿り着けない）。
     * 総件数は現時点の HTTP 契約に現れないため、Service の {@link Page} を実物のまま
     * （モックなしで）検証する。</p>
     */
    @Test
    @DisplayName("(AC-29a) 全件公開のとき一覧の総件数はページ内件数ではなく実総数になる")
    void ac29a_全件公開なら総件数は実総数になる() {
        Long bulkTeamId = insertTeam("総件数チーム", "act-total-team-" + System.nanoTime(),
                "PUBLIC", false, false);
        seedBulkActivities("TEAM", bulkTeamId, 55, "TOTAL29");
        em.flush();
        em.clear();

        Page<ActivityResultEntity> page = activityResultService.listPublicActivities(
                ActivityScopeType.TEAM, bulkTeamId, PageRequest.of(0, 20));

        assertThat(page.getContent()).as("1 ページ目は 20 件").hasSize(20);
        assertThat(page.getTotalElements())
                .as("総件数はページ内件数(20)ではなく実総数(55)であること").isEqualTo(55L);
        assertThat(page.getTotalPages()).as("総ページ数は 3").isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // アサーションヘルパ
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AC-35: 指定 {@code limit} で一覧を叩き、件数が期待どおりで<b>返却全件が公開(a)</b>であることを検証する。
     */
    private void assertLimitReturnsOnlyPublic(Long teamId, String limit, int expectedSize,
                                              List<Long> publicIds) throws Exception {
        List<Long> ids = listIds(teamId, limit);
        assertThat(ids).as("limit=%s の返却件数", limit).hasSize(expectedSize);
        assertThat(ids).as("limit=%s: 返却された全件が公開(a)であること", limit).isSubsetOf(publicIds);
        assertThat(ids).as("limit=%s: id の重複が無いこと", limit).doesNotHaveDuplicates();
    }

    /**
     * <b>フィクスチャ自己検査</b>: 可視性で絞る<b>前</b>の生の並び順
     * （{@code activityDate DESC, id DESC}）の先頭 {@code pageSize} 行に、
     * 非公開行が必ず食い込んでいることを検証する。
     *
     * <p>旧 AC-25 は 101 件すべてを PUBLIC + PUBLISHED で投入していたため、
     * 取得後のメモリフィルタが 1 件も落とさず「歯抜け」が構造的に発生しなかった。
     * その結果、欠陥1 を素通りさせた。<b>ページング系 AC は必ず本メソッドで
     * フィクスチャの妥当性を先に固定する</b>こと。</p>
     *
     * <p>なお論理削除(d) は {@code @SQLRestriction("deleted_at IS NULL")} により
     * 生の並び順にも現れないため、ここで食い込みを担うのは (b) MEMBERS_ONLY と
     * (c) PUBLIC+DRAFT である。</p>
     */
    private void assertHiddenRowsOnFirstPage(Long teamId, int pageSize, Set<Long> publicIds) {
        em.flush();
        em.clear();
        List<Long> rawFirstPage = resultRepository
                .findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                        ActivityScopeType.TEAM, teamId, PageRequest.of(0, pageSize))
                .getContent().stream().map(ActivityResultEntity::getId).toList();
        assertThat(rawFirstPage)
                .as("フィクスチャ要件: 絞り込み前の先頭 %d 行に非公開行が食い込んでいること"
                        + "（全件公開だと歯抜けが再現せず AC が無効になる）", pageSize)
                .anyMatch(id -> !publicIds.contains(id));
    }

    /** AC-8: JSON ノードのキー集合が許可 8 項目と完全一致することを検証する（ホワイトリスト）。 */
    private void assertWhitelistedKeys(JsonNode node, String where) {
        Set<String> actual = keysOf(node);
        assertThat(actual)
                .as("%s: 公開してよいキーは御裁可済みの 8 項目のみ（想定外のキーが増えたら失敗）", where)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_KEYS);

        JsonNode scopeRef = node.get("scopeRef");
        assertThat(scopeRef != null).as("%s: scopeRef が存在すること", where).isTrue();
        assertThat(keysOf(scopeRef))
                .as("%s: scopeRef のキーも想定 3 項目のみ", where)
                .containsExactlyInAnyOrderElementsOf(ALLOWED_SCOPE_REF_KEYS);
    }

    /** AC-9: 除外必須キーが 1 つも含まれないことをキーごとに個別検証する。 */
    private void assertForbiddenKeysAbsent(JsonNode node, String where) {
        for (String forbidden : FORBIDDEN_KEYS) {
            assertThat(node.has(forbidden))
                    .as("%s: 除外必須の項目 '%s' がレスポンスに漏れている", where, forbidden)
                    .isFalse();
        }
    }

    /** AC-9 二重確認: 生値そのものがボディ文字列に現れないこと。 */
    private void assertSecretValuesAbsent(String body, String where) {
        assertThat(body).as("%s: location の生値が漏れている", where).doesNotContain(SECRET_LOCATION);
        assertThat(body).as("%s: fieldValues の生 JSON が漏れている", where).doesNotContain("secret_note");
        assertThat(body).as("%s: attachments の生 JSON が漏れている", where).doesNotContain("file_ids");
        assertThat(body).as("%s: createdBy（ユーザー ID）が漏れている", where)
                .doesNotContain(String.valueOf(SECRET_CREATED_BY));
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<Long> idsOf(JsonNode arrayNode) {
        return java.util.stream.StreamSupport.stream(arrayNode.spliterator(), false)
                .map(n -> n.path("id").asLong())
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MockMvc ヘルパ（未認証・実 Security フィルタチェーン）
    // ═══════════════════════════════════════════════════════════════════════

    /** 認証ヘッダ無しで GET する（実 Security フィルタチェーンを通す）。 */
    private MvcResult perform(String path, Object... uriVars) throws Exception {
        return mockMvc.perform(get(path, uriVars)).andReturn();
    }

    private MvcResult performOk(String path, Object... uriVars) throws Exception {
        return mockMvc.perform(get(path, uriVars)).andExpect(status().isOk()).andReturn();
    }

    /** 200 を期待して {@code $.data} を返す。 */
    private JsonNode getData(String path, Object... uriVars) throws Exception {
        MvcResult result = performOk(path, uriVars);
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = root.get("data");
        assertThat(data != null).as("レスポンスに data が含まれること: %s", path).isTrue();
        return data;
    }

    /** 404（存在秘匿）を期待する。403 / 401 / 200 はすべて失敗。 */
    private void expectNotFound(String path, Object... uriVars) throws Exception {
        mockMvc.perform(get(path, uriVars)).andExpect(status().isNotFound());
    }

    /** 一覧を limit 指定で叩き、返却件数を返す（200 必須）。 */
    private int listSize(Long teamId, String limit) throws Exception {
        return listIds(teamId, limit).size();
    }

    /** 一覧を limit 指定で叩き、返却 id を並び順のまま返す（200 必須）。 */
    private List<Long> listIds(Long teamId, String limit) throws Exception {
        MvcResult result = mockMvc.perform(get(TEAM_ACTIVITY_LIST, teamId).param("limit", limit))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.isArray()).as("data は配列であること（limit=%s）", limit).isTrue();
        return idsOf(data);
    }

    /**
     * AC-31b: 公開一覧の指定ページを Service 経由で取得し id を返す。
     *
     * <p>HTTP 契約は 1 ページ目しか露出しない（{@code limit} のみでページ番号を取らない）ため、
     * ページ通しの重複・取りこぼしは Service の {@link Page} で検証する。</p>
     */
    private List<Long> pageIds(Long teamId, int pageNumber, int pageSize) {
        return activityResultService.listPublicActivities(
                        ActivityScopeType.TEAM, teamId, PageRequest.of(pageNumber, pageSize))
                .getContent().stream().map(ActivityResultEntity::getId).toList();
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // フィクスチャ seed（test profile は ddl-auto=create + flyway 無効のため手動 seed 必須）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * teams を 1 行挿入する。
     *
     * <p>公開性は {@code visibility}（{@code TeamEntity.Visibility}: PUBLIC /
     * GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE）、凍結は {@code archived_at}、
     * 停止相当は {@code deleted_at} で表現する（teams に SUSPENDED 列は存在しない）。
     * {@code TeamRepository#findPublicTeamById} は
     * {@code visibility = PUBLIC AND archived_at IS NULL}（+ {@code @SQLRestriction} で
     * {@code deleted_at IS NULL}）を条件とする。</p>
     */
    private Long insertTeam(String name, String slug, String visibility,
                            boolean archived, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, "
                                + "slug, archived_at, deleted_at, created_at, updated_at) "
                                + "VALUES (:name, '" + visibility + "', 1, 0, 0, :slug, "
                                + (archived ? "NOW()" : "NULL") + ", "
                                + (deleted ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    /**
     * organizations を 1 行挿入する。
     *
     * <p>公開性は {@code visibility}（{@code OrganizationEntity.Visibility}: PUBLIC / PRIVATE）、
     * 凍結は {@code archived_at}、停止相当は {@code deleted_at}
     * （{@code OrganizationRepository} §11.6 が「非アクティブ = {@code deleted_at IS NOT NULL}」と
     * 明記。将来 SUSPENDED 列が追加されたらここも追随する）。</p>
     */
    private Long insertOrganization(String name, String slug, String visibility,
                                    boolean archived, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, archived_at, deleted_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', '" + visibility + "', 'NONE', 1, 0, :slug, "
                                + (archived ? "NOW()" : "NULL") + ", "
                                + (deleted ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    /**
     * activity_results を 1 行挿入する。除外必須項目（location / venue_id / template_id /
     * schedule_id / created_by / field_values / attachments）にはすべて「漏れたら分かる」値を入れる。
     *
     * <p><b>時刻列は必ず {@link LocalTime} パラメータで bind すること（SQL 文字列リテラル禁止）</b>:
     * {@code application-test.yml} は {@code hibernate.jdbc.time_zone: UTC} を設定しており、
     * Hibernate は TIME 列を「DB は UTC で保持している」前提で読み書きする。
     * 一方 JVM は JST で動くため、{@code '10:00:00'} という<b>文字列リテラル</b>で INSERT すると
     * 書き込み側だけが TZ 変換を経ず、読み出し時に UTC 10:00 → JST 19:00 と <b>9 時間ずれて</b>返る。
     * {@code setParameter("timeStart", LocalTime.of(10, 0))} と bind すれば書き込み側も
     * 同じ TZ 経路を通り、実アプリ（JPA 経由で LocalTime を保存）と等価な往復になる。
     * 実際に本テストの AC-8 実値検証が {@code "19:00:00"} を受け取って落ちた
     * （memory {@code feedback_it_fixture_datetime_tz_bind} と同型のフィクスチャ不整合）。</p>
     *
     * @param timeStart 開始時刻（{@code null} 可 = AC-23 用）
     * @param timeEnd   終了時刻（{@code null} 可 = AC-23 用）
     * @param description 説明（{@code null} 可 = AC-24 用）
     */
    private Long insertActivity(String scopeType, Long scopeId, String title, String visibility,
                                String status, boolean deleted, String timeStart, String timeEnd,
                                String description) {
        String sql = "INSERT INTO activity_results ("
                + "scope_type, scope_id, template_id, title, activity_date, "
                + "activity_time_start, activity_time_end, location, venue_id, description, "
                + "field_values, attachments, visibility, status, schedule_id, created_by, "
                + "deleted_at, created_at, updated_at) VALUES ("
                + "'" + scopeType + "', :scopeId, " + SECRET_TEMPLATE_ID + ", :title, '" + ACTIVITY_DATE + "', "
                + (timeStart == null ? "NULL" : ":timeStart") + ", "
                + (timeEnd == null ? "NULL" : ":timeEnd") + ", "
                + ":location, " + SECRET_VENUE_ID + ", "
                + (description == null ? "NULL" : ":description") + ", "
                + ":fieldValues, :attachments, "
                + "'" + visibility + "', '" + status + "', " + SECRET_SCHEDULE_ID + ", " + SECRET_CREATED_BY + ", "
                + (deleted ? "NOW()" : "NULL") + ", NOW(), NOW())";

        var query = em.createNativeQuery(sql)
                .setParameter("scopeId", scopeId)
                .setParameter("title", title)
                .setParameter("location", SECRET_LOCATION)
                .setParameter("fieldValues", SECRET_FIELD_VALUES)
                .setParameter("attachments", SECRET_ATTACHMENTS);
        // TIME 列は LocalTime bind（上記 Javadoc の TZ 経路一致）。null は SQL の NULL リテラルのまま。
        if (timeStart != null) {
            query.setParameter("timeStart", LocalTime.parse(timeStart));
        }
        if (timeEnd != null) {
            query.setParameter("timeEnd", LocalTime.parse(timeEnd));
        }
        if (description != null) {
            query.setParameter("description", description);
        }
        query.executeUpdate();

        return ((Number) em.createNativeQuery(
                        "SELECT id FROM activity_results WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    /** 指定スコープ配下に PUBLIC + PUBLISHED の記録を {@code count} 件まとめて作る。 */
    private void seedBulkActivities(String scopeType, Long scopeId, int count, String tag) {
        long nonce = System.nanoTime();
        for (int i = 0; i < count; i++) {
            insertActivity(scopeType, scopeId, tag + "-" + nonce + "-" + i,
                    "PUBLIC", "PUBLISHED", false, "10:00:00", "11:00:00", "一括生成");
        }
        em.flush();
        em.clear();
    }

    /**
     * <b>ページング系 AC 共通の混在フィクスチャ</b>。4 種の行を<b>交互に</b>投入する。
     *
     * <table>
     *   <caption>投入する 4 種</caption>
     *   <tr><th>種別</th><th>visibility</th><th>status</th><th>deleted_at</th><th>匿名から見えるか</th></tr>
     *   <tr><td>(a)</td><td>PUBLIC</td><td>PUBLISHED</td><td>NULL</td><td>見える（唯一の正解）</td></tr>
     *   <tr><td>(b)</td><td>MEMBERS_ONLY</td><td>PUBLISHED</td><td>NULL</td><td>見えない</td></tr>
     *   <tr><td>(c)</td><td>PUBLIC</td><td>DRAFT</td><td>NULL</td><td>見えない</td></tr>
     *   <tr><td>(d)</td><td>PUBLIC</td><td>PUBLISHED</td><td>NOT NULL</td><td>見えない</td></tr>
     * </table>
     *
     * <p>並び順は {@code activityDate DESC, id DESC}（全行が同一日付のため実質 id DESC）なので、
     * <b>後から入れた行ほど先頭ページに来る</b>。4 種を件数比に応じたラウンドロビンで
     * 均等に混ぜることで、非公開行が必ず先頭ページに食い込む配置になる。
     * この配置は {@link #assertHiddenRowsOnFirstPage} で各 AC が明示的に自己検査する。</p>
     *
     * @return 公開(a) 行の id を<b>一覧の並び順（id DESC）</b>で並べたリスト
     */
    private List<Long> seedInterleaved(Long teamId, String tag, int publicCount,
                                       int membersOnlyCount, int draftCount, int deletedCount) {
        int[] planned = { publicCount, membersOnlyCount, draftCount, deletedCount };
        String[][] spec = {
                { "PUBLIC", "PUBLISHED", "false" },      // (a) 公開
                { "MEMBERS_ONLY", "PUBLISHED", "false" }, // (b) 会員限定
                { "PUBLIC", "DRAFT", "false" },           // (c) 下書き
                { "PUBLIC", "PUBLISHED", "true" },        // (d) 論理削除
        };
        int total = publicCount + membersOnlyCount + draftCount + deletedCount;
        int[] issued = new int[4];
        long nonce = System.nanoTime();
        List<Long> publicIds = new ArrayList<>();

        for (int n = 0; n < total; n++) {
            // 「予定件数に対する消化率」が最小の種別を選ぶ = 件数比に応じた決定的なラウンドロビン
            int kind = -1;
            double bestRatio = Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                if (issued[k] >= planned[k]) {
                    continue;
                }
                double ratio = (double) issued[k] / planned[k];
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    kind = k;
                }
            }
            issued[kind]++;
            Long id = insertActivity("TEAM", teamId,
                    tag + "-k" + kind + "-" + nonce + "-" + n,
                    spec[kind][0], spec[kind][1], Boolean.parseBoolean(spec[kind][2]),
                    "10:00:00", "11:00:00", "混在フィクスチャ");
            if (kind == 0) {
                publicIds.add(id);
            }
        }
        em.flush();
        em.clear();

        // 一覧の並び順（activityDate DESC, id DESC）に合わせて id 降順で返す
        return publicIds.stream().sorted(Comparator.reverseOrder()).toList();
    }

    /**
     * AC-33: memberships に ACTIVE な行を 1 件挿入する
     * （{@code AccessControlService#isMember} は memberships を参照する。F00.5 Phase 3）。
     */
    private void insertMembership(Long userId, String scopeType, Long scopeId) {
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, "
                                + "joined_at, created_at, updated_at) "
                                + "VALUES (:userId, '" + scopeType + "', :scopeId, 'MEMBER', "
                                + "NOW(), NOW(), NOW())")
                .setParameter("userId", userId)
                .setParameter("scopeId", scopeId)
                .executeUpdate();
        em.flush();
    }

}
