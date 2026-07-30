package com.mannschaft.app.survey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.survey.dto.CreateOptionRequest;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2508 AC-10: 非JSTユーザーの {@link LocalDateTime} HTTP 往復（POST → 実MySQL保存 → GET）
 * を実機で実証する統合テスト。
 *
 * <h2>対象エンドポイントの選定理由</h2>
 * <p>{@code POST /api/v1/{scopeType}/{scopeId}/surveys}（{@code SurveyController#createSurvey}）と
 * {@code GET .../{surveyId}}（{@code #getSurvey}）を選んだ。理由:</p>
 * <ul>
 *   <li>{@link CreateSurveyRequest#getStartsAt()}/{@link CreateSurveyRequest#getExpiresAt()} が
 *       素の {@link LocalDateTime}（{@code OffsetDateTime} ではない）であり、本 Issue の是正対象
 *       {@code LocalDateTimeTimezoneDeserializer}/{@code LocalDateTimeTimezoneSerializer} を
 *       入力・出力の両方向で実際に通る数少ない HTTP エンドポイントである
 *       （他の多くの日時入力 DTO は {@code OffsetDateTime} で受けており対象外）。</li>
 *   <li>{@code GET .../{surveyId}} が {@code SurveyResponse.SurveyScheduleDto} 経由で同じ
 *       {@code startsAt}/{@code expiresAt} を読み戻せるため、POST→GET の完全往復を1エンドポイントで検証できる。</li>
 *   <li>作成に必要な前提（チームメンバーであること）が {@code memberships} 1行の投入だけで満たせ、
 *       他ドメインへの波及が少ない。</li>
 * </ul>
 *
 * <h2>検証方針</h2>
 * <ul>
 *   <li>{@code users.timezone = 'America/Los_Angeles'} の実ユーザーで、オフセット付き送信
 *       （FE が今後送る形）・オフセット無し送信（旧クライアント）の<b>両方</b>を検証する。</li>
 *   <li>LA の夏時間境界（夏 -07:00 / 冬 -08:00）を跨ぐ2つの日付を使い分け、
 *       {@code ZoneId.of("America/Los_Angeles")} の実際のオフセット遷移で正しさを裏取りする
 *       （オフセット文字列はハードコードせず {@link ZonedDateTime} から機械的に導出する）。</li>
 *   <li>{@code Asia/Tokyo} ユーザーでも同じ検証を行い、恒等変換（既存挙動不変）を回帰ガードとして固定する。</li>
 *   <li>実 STOMP/実 HTTP 経路（{@code JwtAuthenticationFilter} → {@code UserTimezoneFilter} →
 *       {@code LocalDateTimeTimezoneDeserializer}/{@code Serializer}）をモックせず通す
 *       （{@code ChatChannelSubscriptionAuthzIntegrationTest} と同型のテスト品質方針）。</li>
 * </ul>
 *
 * <p>基底の共有 MOCK コンテキストとは別に RANDOM_PORT コンテキストを起動するため、
 * {@code @DirtiesContext(AFTER_CLASS)} で TestContext キャッシュ分裂の波及を防ぐ。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2508 AC-10: 非JSTユーザーのLocalDateTime HTTP往復（POST→DB→GET）実機統合テスト")
class SurveyDateTimeTimezoneHttpRoundTripIntegrationTest extends AbstractMySqlIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    /** クライアント側で生 JSON を組み立てるための素の ObjectMapper（サーバの @Primary Bean とは無関係）。 */
    private final ObjectMapper plainMapper = new ObjectMapper();

    private final RestTemplate restTemplate = new RestTemplate();

    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            for (Long teamId : createdTeamIds) {
                em.createNativeQuery("DELETE FROM surveys WHERE scope_type = 'TEAM' AND scope_id = :tid")
                        .setParameter("tid", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :tid")
                        .setParameter("tid", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id = :tid")
                        .setParameter("tid", teamId).executeUpdate();
            }
            for (Long userId : createdUserIds) {
                em.createNativeQuery("DELETE FROM users WHERE id = :uid")
                        .setParameter("uid", userId).executeUpdate();
            }
            return null;
        });
        createdTeamIds.clear();
        createdUserIds.clear();
    }

    /** 指定 timezone の実ユーザーを users テーブルへ確実にコミットして作成する。 */
    private Long insertUser(String timezone) {
        String email = "ac10-httprt." + UUID.randomUUID() + "@example.com";
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long userId = tx.execute(status -> {
            em.createNativeQuery(
                    "INSERT INTO users ("
                            + "email, last_name, first_name, display_name, status, "
                            + "is_searchable, handle_searchable, contact_approval_required, "
                            + "online_visibility, dm_receive_from, encryption_key_version, "
                            + "locale, timezone, reporting_restricted, follow_list_visibility, "
                            + "care_notification_enabled, offline_only, "
                            + "created_at, updated_at) "
                            + "VALUES (:email, 'AC10', 'HttpRT', 'AC10 HttpRT', 'ACTIVE', "
                            + "1, 1, 1, "
                            + "'NOBODY', 'ANYONE', 1, "
                            + "'ja', :tz, 0, 'PUBLIC', "
                            + "1, 0, "
                            + "NOW(), NOW())")
                    .setParameter("email", email)
                    .setParameter("tz", timezone)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                    .setParameter("email", email)
                    .getSingleResult()).longValue();
        });
        createdUserIds.add(userId);
        return userId;
    }

    /** 使い捨てチームを作成し、指定ユーザーを MEMBER として加入させる。チームの slug を返す。 */
    private String createTeamAndMembership(Long userId) {
        TeamEntity team = teamRepository.save(TeamEntity.builder()
                .slug("ac10-httprt-" + UUID.randomUUID().toString().substring(0, 8))
                .name("AC-10 HTTP往復検証チーム")
                .template("sports")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build());
        createdTeamIds.add(team.getId());

        membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(team.getId())
                .roleKind(RoleKind.MEMBER)
                .build());

        return team.getSlug();
    }

    /**
     * {@code startsAt}/{@code expiresAt} だけを raw 文字列に差し替えた {@link CreateSurveyRequest} の
     * JSON ボディを組み立てる（他フィールドは素の ObjectMapper で直列化し整合性を保つ）。
     */
    private String surveyRequestJson(String startsAtRaw, String expiresAtRaw) throws Exception {
        String base = plainMapper.writeValueAsString(new CreateSurveyRequest(
                "AC-10 HTTP往復検証アンケート",          // title
                "非JSTユーザーのLocalDateTime往復検証",   // description
                false,                                  // isAnonymous
                false,                                  // allowMultipleSubmissions
                "AFTER_RESPONSE",                       // resultsVisibility
                "ALL",                                  // distributionMode
                "CREATOR_AND_ADMIN",                    // unrespondedVisibility
                false,                                  // autoPostToTimeline
                null,                                   // seriesId
                null,                                   // remindBeforeHours
                null,                                   // startsAt（後で raw 差し替え）
                null,                                   // expiresAt（後で raw 差し替え）
                List.of(new CreateQuestionRequest(
                        "SINGLE_CHOICE", "参加しますか？", true, 0, null, null, null, null, null,
                        List.of(new CreateOptionRequest("参加", 0),
                                new CreateOptionRequest("不参加", 1)))),
                null,                                   // targetUserIds
                null,                                   // resultViewerUserIds
                false,                                  // includeSupporters
                false                                   // teamBreakdownEnabled
        ));
        ObjectNode node = (ObjectNode) plainMapper.readTree(base);
        node.put("startsAt", startsAtRaw);
        node.put("expiresAt", expiresAtRaw);
        return plainMapper.writeValueAsString(node);
    }

    private HttpHeaders authHeaders(Long userId) {
        String jwt = authTokenService.issueAccessToken(userId, List.of("USER"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        return headers;
    }

    /**
     * 実 HTTP で POST → GET を1往復し、GET 応答の {@code data.survey.schedule} ノードを返す。
     *
     * <p>認証は実 JWT（{@link AuthTokenService#issueAccessToken}）で確立し、
     * {@code UserTimezoneFilter} が実際に {@code users.timezone} を解決した状態で
     * デシリアライズ・シリアライズが走る（モックなし）。</p>
     */
    private JsonNode postThenGet(Long userId, String teamSlug, String startsAtRaw, String expiresAtRaw)
            throws Exception {
        HttpHeaders headers = authHeaders(userId);
        String body = surveyRequestJson(startsAtRaw, expiresAtRaw);
        String baseUrl = "http://localhost:" + port + "/api/v1/teams/" + teamSlug + "/surveys";

        ResponseEntity<String> postResponse = restTemplate.exchange(
                baseUrl, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(postResponse.getStatusCode().value())
                .as("AC-10: アンケート作成が201で成功すること（本文=%s）".formatted(postResponse.getBody()))
                .isEqualTo(201);
        JsonNode postJson = plainMapper.readTree(postResponse.getBody());
        long surveyId = postJson.get("data").get("survey").get("id").asLong();

        ResponseEntity<String> getResponse = restTemplate.exchange(
                baseUrl + "/" + surveyId, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(getResponse.getStatusCode().value())
                .as("AC-10: アンケート詳細取得が200で成功すること（本文=%s）".formatted(getResponse.getBody()))
                .isEqualTo(200);
        // レスポンス構造: ApiResponse{data: SurveyDetailResponse{survey: SurveyResponse{schedule: SurveyScheduleDto}}}
        // SurveyDetailResponse#getSurvey() → SurveyResponse#getSchedule() の実物どおりのパスを辿る
        // （SurveyDetailResponse.java / SurveyResponse.java で確認済み）。
        JsonNode getJson = plainMapper.readTree(getResponse.getBody());
        return getJson.get("data").get("survey").get("schedule");
    }

    @Test
    @DisplayName("AC-10: LA(夏 -07:00・オフセット付き送信=FEが今後送る形) → POST→DB→GETで入力した壁時計が保持される")
    void LAユーザー夏時間オフセット付き送信の往復() throws Exception {
        // Arrange
        Long userId = insertUser("America/Los_Angeles");
        String teamSlug = createTeamAndMembership(userId);
        ZoneId la = ZoneId.of("America/Los_Angeles");

        // 2026-07-15 は北米夏時間（PDT・-07:00）区間内の日付
        LocalDateTime startsAtWall = LocalDateTime.of(2026, 7, 15, 9, 0, 0);
        LocalDateTime expiresAtWall = LocalDateTime.of(2026, 7, 22, 18, 30, 0);
        String startsAtRaw = ZonedDateTime.of(startsAtWall, la).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String expiresAtRaw = ZonedDateTime.of(expiresAtWall, la).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Act
        JsonNode schedule = postThenGet(userId, teamSlug, startsAtRaw, expiresAtRaw);

        // Assert: 入力したLA壁時計がそのまま返ること（瞬間をLA壁時計へ変換して比較）
        OffsetDateTime returnedStartsAt = OffsetDateTime.parse(schedule.get("startsAt").asText());
        OffsetDateTime returnedExpiresAt = OffsetDateTime.parse(schedule.get("expiresAt").asText());
        assertThat(returnedStartsAt.atZoneSameInstant(la).toLocalDateTime())
                .as("AC-10: LA夏時間・オフセット付き送信のstartsAtが入力した壁時計のまま往復すること")
                .isEqualTo(startsAtWall);
        assertThat(returnedExpiresAt.atZoneSameInstant(la).toLocalDateTime())
                .as("AC-10: expiresAt も同様に保持されること")
                .isEqualTo(expiresAtWall);
        // GET応答が実際にLAのオフセット(-07:00)で返ること（+09:00に化けていないことの明示的固定）
        assertThat(returnedStartsAt.getOffset())
                .as("AC-10: LAユーザーへのGET応答はLA夏時間のオフセット(-07:00)で返ること")
                .isEqualTo(ZoneOffset.ofHours(-7));
    }

    @Test
    @DisplayName("AC-10: LA(冬 -08:00・オフセット無し送信=旧クライアント) → POST→DB→GETで入力した壁時計が保持される")
    void LAユーザー冬時間オフセット無し送信の往復() throws Exception {
        // Arrange
        Long userId = insertUser("America/Los_Angeles");
        String teamSlug = createTeamAndMembership(userId);
        ZoneId la = ZoneId.of("America/Los_Angeles");

        // 2027-01-15 は北米標準時（PST・-08:00）区間内の日付
        LocalDateTime startsAtWall = LocalDateTime.of(2027, 1, 15, 9, 0, 0);
        LocalDateTime expiresAtWall = LocalDateTime.of(2027, 1, 22, 18, 30, 0);
        // 旧クライアント: オフセット無しでユーザーの壁時計をそのまま送る
        String startsAtRaw = startsAtWall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String expiresAtRaw = expiresAtWall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Act
        JsonNode schedule = postThenGet(userId, teamSlug, startsAtRaw, expiresAtRaw);

        // Assert
        OffsetDateTime returnedStartsAt = OffsetDateTime.parse(schedule.get("startsAt").asText());
        OffsetDateTime returnedExpiresAt = OffsetDateTime.parse(schedule.get("expiresAt").asText());
        assertThat(returnedStartsAt.atZoneSameInstant(la).toLocalDateTime())
                .as("AC-10: LA冬時間・オフセット無し送信のstartsAtが「LAユーザーの壁時計」として解釈され往復すること")
                .isEqualTo(startsAtWall);
        assertThat(returnedExpiresAt.atZoneSameInstant(la).toLocalDateTime())
                .as("AC-10: expiresAt も同様に保持されること")
                .isEqualTo(expiresAtWall);
        assertThat(returnedStartsAt.getOffset())
                .as("AC-10: LAユーザーへのGET応答はLA冬時間のオフセット(-08:00)で返ること")
                .isEqualTo(ZoneOffset.ofHours(-8));
    }

    @Test
    @DisplayName("AC-10回帰: JST(+09:00・オフセット付き送信) → 恒等変換のまま既存挙動を維持する")
    void JSTユーザーオフセット付き送信の往復回帰() throws Exception {
        // Arrange
        Long userId = insertUser("Asia/Tokyo");
        String teamSlug = createTeamAndMembership(userId);

        LocalDateTime startsAtWall = LocalDateTime.of(2026, 8, 1, 10, 0, 0);
        LocalDateTime expiresAtWall = LocalDateTime.of(2026, 8, 8, 12, 0, 0);
        String startsAtRaw = startsAtWall.atOffset(ZoneOffset.ofHours(9))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String expiresAtRaw = expiresAtWall.atOffset(ZoneOffset.ofHours(9))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Act
        JsonNode schedule = postThenGet(userId, teamSlug, startsAtRaw, expiresAtRaw);

        // Assert: 恒等変換（既存挙動と完全一致）であること
        OffsetDateTime returnedStartsAt = OffsetDateTime.parse(schedule.get("startsAt").asText());
        OffsetDateTime returnedExpiresAt = OffsetDateTime.parse(schedule.get("expiresAt").asText());
        assertThat(returnedStartsAt.toLocalDateTime())
                .as("AC-10回帰: JSTユーザー・オフセット付き送信は恒等変換のまま保存・返却されること")
                .isEqualTo(startsAtWall);
        assertThat(returnedExpiresAt.toLocalDateTime())
                .as("AC-10回帰: expiresAt も恒等変換のまま保持されること")
                .isEqualTo(expiresAtWall);
        assertThat(returnedStartsAt.getOffset())
                .as("AC-10回帰: JSTユーザーへのGET応答は+09:00で返ること")
                .isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    @DisplayName("AC-10回帰: JST(オフセット無し送信=旧クライアント) → 恒等変換のまま既存挙動を維持する")
    void JSTユーザーオフセット無し送信の往復回帰() throws Exception {
        // Arrange
        Long userId = insertUser("Asia/Tokyo");
        String teamSlug = createTeamAndMembership(userId);

        LocalDateTime startsAtWall = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        LocalDateTime expiresAtWall = LocalDateTime.of(2026, 9, 8, 12, 0, 0);
        String startsAtRaw = startsAtWall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String expiresAtRaw = expiresAtWall.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // Act
        JsonNode schedule = postThenGet(userId, teamSlug, startsAtRaw, expiresAtRaw);

        // Assert
        OffsetDateTime returnedStartsAt = OffsetDateTime.parse(schedule.get("startsAt").asText());
        OffsetDateTime returnedExpiresAt = OffsetDateTime.parse(schedule.get("expiresAt").asText());
        assertThat(returnedStartsAt.toLocalDateTime())
                .as("AC-10回帰: JSTユーザー・オフセット無し送信は恒等変換のまま保存・返却されること")
                .isEqualTo(startsAtWall);
        assertThat(returnedExpiresAt.toLocalDateTime())
                .as("AC-10回帰: expiresAt も恒等変換のまま保持されること")
                .isEqualTo(expiresAtWall);
        assertThat(returnedStartsAt.getOffset())
                .as("AC-10回帰: JSTユーザーへのGET応答は+09:00で返ること")
                .isEqualTo(ZoneOffset.ofHours(9));
    }
}
