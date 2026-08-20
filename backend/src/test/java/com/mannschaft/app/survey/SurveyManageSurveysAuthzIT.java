package com.mannschaft.app.survey;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.survey.dto.DuplicateSurveyRequest;
import com.mannschaft.app.survey.service.SurveyAccessGuard;
import com.mannschaft.app.survey.service.SurveyRemindService;
import com.mannschaft.app.survey.service.SurveyResponseService;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveySeriesService;
import com.mannschaft.app.survey.service.SurveyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-041 試練: 設計書 F05.4 §320 の「ADMIN+」＝「ADMIN、または {@code MANAGE_SURVEYS} を持つ
 * DEPUTY_ADMIN」を survey ドメインの 9 箇所と可視性層で成立させるための受け入れテスト。
 *
 * <h2>本テストが赤であるべき理由</h2>
 * <p>現行の 9 箇所は {@code accessControlService.isAdminOrAbove(...)} を呼んでおり、これは
 * {@code ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}}（{@code AccessControlService:52}）との照合に
 * すぎない。すなわち <b>{@code MANAGE_SURVEYS} を一切持たない DEPUTY_ADMIN が全通しする</b>。
 * また可視性層 {@code SurveyVisibilityResolver#isScopeAdmin}（:568）は
 * {@code hasRoleOrAbove(scope, "ADMIN")} であり、DEPUTY_ADMIN（priority 3）は ADMIN（2）より
 * 弱いため<b>権限を持っていても常に false</b> になる。すなわち仕様の委任は現状どちらの側にも
 * 成立していない。</p>
 *
 * <h2>受け入れ条件</h2>
 * <ul>
 *   <li>AC-16: 9 箇所すべてで権限なし DEPUTY_ADMIN が拒否される</li>
 *   <li>AC-17: 作成者は権限なしでも従来どおり通る（非回帰）</li>
 *   <li>AC-18: {@code remind} を権限なし DEPUTY_ADMIN が叩くと通知が 1 件も送られない（副作用ゼロ）</li>
 *   <li>AC-19: 匿名アンケートの個別回答は ADMIN でも従来どおり遮断（非回帰）</li>
 *   <li>AC-20: {@code MANAGE_SURVEYS} を持つ DEPUTY_ADMIN が結果閲覧でフルアクセスを得る</li>
 *   <li>AC-21: 権限なし DEPUTY_ADMIN は結果閲覧不可</li>
 *   <li>AC-22: {@code DRAFT} / {@code ARCHIVED} は権限があっても貫通しない（status 軸の非貫通）</li>
 *   <li>AC-23: 他スコープの ADMIN は通らない</li>
 *   <li>AC-24: 対象行 0 件のバッチで追加 SQL が 0 本</li>
 *   <li>AC-25: SQL 本数の計器（{@code SqlIntentCounter}）が実際に捕捉している（生存証明）</li>
 *   <li>AC-26: 権限の先読みがスコープ数に比例しない</li>
 * </ul>
 *
 * <p>{@code MANAGE_SURVEYS} のカタログ行は、test profile が Flyway 無効であるため
 * 第一陣 migration の本文をそのまま実行して作る（テスト内で捏造しない）。</p>
 */
@Transactional
@DisplayName("CMP-041: survey の ADMIN+ が MANAGE_SURVEYS 保有 DEPUTY_ADMIN へ委任される")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SurveyManageSurveysAuthzIT extends AbstractMySqlIntegrationTest {

    private static final String MIGRATION_RESOURCE =
            "db/migration/V187.20260819090014__add_manage_surveys_to_catalog.sql";
    private static final String PERMISSION = "MANAGE_SURVEYS";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SurveyService surveyService;
    @Autowired
    private SurveyRemindService surveyRemindService;
    @Autowired
    private SurveyResponseService surveyResponseService;
    @Autowired
    private SurveyAccessGuard surveyAccessGuard;
    @Autowired
    private SurveyResultService surveyResultService;
    @Autowired
    private SurveySeriesService surveySeriesService;
    @Autowired
    private ContentVisibilityChecker checker;
    @Autowired
    private com.mannschaft.app.role.repository.UserRoleRepository userRoleRepository;

    private Long teamId;
    private Long otherTeamId;
    private Long orgId;

    /** ADMIN（当該チーム）。 */
    private Long adminUserId;
    /** MANAGE_SURVEYS を権限グループ経由で保有する DEPUTY_ADMIN。 */
    private Long deputyWithPermissionId;
    /** MANAGE_SURVEYS を一切持たない DEPUTY_ADMIN。 */
    private Long deputyWithoutPermissionId;
    /** アンケート作成者（一般 MEMBER・権限なし）。 */
    private Long creatorUserId;
    /** 他チームの ADMIN。 */
    private Long otherTeamAdminId;

    @BeforeEach
    void setUp() {
        seedRoles();
        seedManageSurveysFromMigration();

        teamId = insertTeam();
        otherTeamId = insertTeam();
        orgId = insertOrganization();
        insertTeamOrgMembership(teamId, orgId);

        adminUserId = insertUser();
        grantRole(adminUserId, "ADMIN", teamId, null);

        deputyWithPermissionId = insertUser();
        grantRole(deputyWithPermissionId, "DEPUTY_ADMIN", teamId, null);
        Long group = insertPermissionGroup(teamId);
        addPermissionToGroup(group, PERMISSION);
        assignGroupToUser(deputyWithPermissionId, group);

        deputyWithoutPermissionId = insertUser();
        grantRole(deputyWithoutPermissionId, "DEPUTY_ADMIN", teamId, null);

        creatorUserId = insertUser();
        grantRole(creatorUserId, "MEMBER", teamId, null);

        otherTeamAdminId = insertUser();
        grantRole(otherTeamAdminId, "ADMIN", otherTeamId, null);

        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-16: 9 箇所すべてで権限なし DEPUTY_ADMIN が拒否される
    // =====================================================================

    @Test
    @DisplayName("AC-16-1: SurveyService.extendDeadline — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_1_extendDeadline() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                LocalDateTime.now().plusDays(1), null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyService.extendDeadline(
                "TEAM", teamId, surveyId, LocalDateTime.now().plusDays(3), deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者に締切延長を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-2: SurveyService.duplicateSurvey — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_2_duplicateSurvey() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyService.duplicateSurvey(
                "TEAM", teamId, surveyId, new DuplicateSurveyRequest(), deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者にアンケート複製を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-3: SurveyRemindService.remind — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_3_remind() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                LocalDateTime.now().plusDays(1), null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyRemindService.remind(surveyId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者に督促送信を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-4: SurveyResponseService.getResponseByUser — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_4_getResponseByUser() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, creatorUserId);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyResponseService
                .getResponseByUser(surveyId, creatorUserId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者に個別回答の閲覧を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-5: SurveyAccessGuard.checkCanManage — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_5_accessGuardCheckCanManage() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyAccessGuard.checkCanManage(deputyWithoutPermissionId, surveyId))
                .as("MANAGE_SURVEYS を持たない副管理者に管理操作を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-6: SurveyResultService.getRespondents — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_6_getRespondents() {
        // unresponded_visibility は CREATOR_AND_ADMIN（既定）のため、
        // 権限なし副管理者が通るとすれば ADMIN+ 経路（isAdminOrAbove）以外にない。
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyResultService.getRespondents(surveyId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者に回答者一覧の閲覧を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-7: SurveyResultService.exportResultsCsv — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_7_exportResultsCsv() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyResultService
                .exportResultsCsv("TEAM", teamId, surveyId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者に CSV エクスポートを許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-8: SurveyResultService.getTeamBreakdown — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_8_getTeamBreakdown() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyResultService.getTeamBreakdown(surveyId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者にチーム別内訳の閲覧を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-16-9: SurveySeriesService.compareSeries — 権限なし DEPUTY_ADMIN は拒否")
    void ac16_9_compareSeries() {
        String seriesId = "cmp041-series-" + SEQ.incrementAndGet();
        insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, seriesId, false);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveySeriesService.compareSeries(seriesId, deputyWithoutPermissionId))
                .as("MANAGE_SURVEYS を持たない副管理者にシリーズ比較の閲覧を許してはならない")
                .isInstanceOf(BusinessException.class);
    }

    /**
     * AC-16 の陽性対照: 権限を持つ DEPUTY_ADMIN は同じ 9 経路で通ること。
     *
     * <p>拒否側だけを締めると「DEPUTY_ADMIN を一律に締め出す」実装でも緑になり、
     * 委任の実装になっていないことを見逃す。</p>
     */
    @Test
    @DisplayName("AC-16-陽性対照: MANAGE_SURVEYS を持つ DEPUTY_ADMIN は 9 経路すべてで通る")
    void ac16_陽性対照_権限保有DEPUTY_ADMINは通る() {
        String seriesId = "cmp041-series-ok-" + SEQ.incrementAndGet();
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                LocalDateTime.now().plusDays(1), seriesId, false);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, creatorUserId);
        em.flush();
        em.clear();

        Long u = deputyWithPermissionId;
        assertThatCode(() -> surveyAccessGuard.checkCanManage(u, surveyId))
                .as("checkCanManage").doesNotThrowAnyException();
        assertThatCode(() -> surveyResultService.getRespondents(surveyId, u))
                .as("getRespondents").doesNotThrowAnyException();
        assertThatCode(() -> surveyResultService.exportResultsCsv("TEAM", teamId, surveyId, u))
                .as("exportResultsCsv").doesNotThrowAnyException();
        assertThatCode(() -> surveyResultService.getTeamBreakdown(surveyId, u))
                .as("getTeamBreakdown").doesNotThrowAnyException();
        assertThatCode(() -> surveyResponseService.getResponseByUser(surveyId, creatorUserId, u))
                .as("getResponseByUser").doesNotThrowAnyException();
        assertThatCode(() -> surveySeriesService.compareSeries(seriesId, u))
                .as("compareSeries").doesNotThrowAnyException();
        assertThatCode(() -> surveyService.duplicateSurvey(
                "TEAM", teamId, surveyId, new DuplicateSurveyRequest(), u))
                .as("duplicateSurvey").doesNotThrowAnyException();
        assertThatCode(() -> surveyService.extendDeadline(
                "TEAM", teamId, surveyId, LocalDateTime.now().plusDays(5), u))
                .as("extendDeadline").doesNotThrowAnyException();
        assertThatCode(() -> surveyRemindService.remind(surveyId, u))
                .as("remind").doesNotThrowAnyException();
    }

    // =====================================================================
    // AC-17: 作成者の非回帰
    // =====================================================================

    @Test
    @DisplayName("AC-17: 作成者は MANAGE_SURVEYS を持たなくても従来どおり通る（非回帰）")
    void ac17_作成者は権限なしでも通る() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                LocalDateTime.now().plusDays(1), null, false);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, creatorUserId);
        em.flush();
        em.clear();

        assertThatCode(() -> surveyAccessGuard.checkCanManage(creatorUserId, surveyId))
                .as("作成者の checkCanManage").doesNotThrowAnyException();
        assertThatCode(() -> surveyResultService.getRespondents(surveyId, creatorUserId))
                .as("作成者の getRespondents").doesNotThrowAnyException();
        assertThatCode(() -> surveyResultService.exportResultsCsv("TEAM", teamId, surveyId, creatorUserId))
                .as("作成者の exportResultsCsv").doesNotThrowAnyException();
        assertThatCode(() -> surveyResponseService.getResponseByUser(surveyId, creatorUserId, creatorUserId))
                .as("作成者の getResponseByUser").doesNotThrowAnyException();
        assertThatCode(() -> surveyService.duplicateSurvey(
                "TEAM", teamId, surveyId, new DuplicateSurveyRequest(), creatorUserId))
                .as("作成者の duplicateSurvey").doesNotThrowAnyException();
        assertThatCode(() -> surveyService.extendDeadline(
                "TEAM", teamId, surveyId, LocalDateTime.now().plusDays(5), creatorUserId))
                .as("作成者の extendDeadline").doesNotThrowAnyException();
    }

    // =====================================================================
    // AC-18: remind の副作用ゼロ
    // =====================================================================

    /**
     * AC-18: 権限なし DEPUTY_ADMIN の {@code remind} は 403 を返すだけでなく、
     * 通知を 1 件も生成してはならない。
     *
     * <p>403 だけを見て緑にすると、「認可の後ろで既に通知を送っていた」型の欠陥
     * （例外前に副作用が確定している）を見逃す。件数の差分で副作用ゼロを直接測る。</p>
     */
    @Test
    @DisplayName("AC-18: 権限なし DEPUTY_ADMIN の remind は通知を 1 件も生成しない")
    void ac18_remind拒否時に通知が生成されない() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                LocalDateTime.now().plusDays(1), null, false);
        em.flush();
        em.clear();

        long before = countNotifications();

        assertThatThrownBy(() -> surveyRemindService.remind(surveyId, deputyWithoutPermissionId))
                .isInstanceOf(BusinessException.class);
        em.flush();

        assertThat(countNotifications())
                .as("拒否された督促で通知が 1 件でも生成されてはならない（副作用ゼロ）")
                .isEqualTo(before);
        assertThat(remindCounterOf(surveyId))
                .as("拒否された督促で manual_remind_count が進んではならない")
                .isZero();
        assertThat(lastRemindedAtOf(surveyId))
                .as("拒否された督促で last_reminded_at が更新されてはならない")
                .isNull();
    }

    // =====================================================================
    // AC-19: 匿名アンケートの非回帰
    // =====================================================================

    @Test
    @DisplayName("AC-19: 匿名アンケートの個別回答は ADMIN でも遮断される（非回帰）")
    void ac19_匿名アンケートはADMINでも個別回答不可() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, true);
        Long questionId = insertQuestion(surveyId);
        insertResponse(surveyId, questionId, creatorUserId);
        em.flush();
        em.clear();

        assertThatThrownBy(() -> surveyResponseService
                .getResponseByUser(surveyId, creatorUserId, adminUserId))
                .as("匿名アンケートの個別回答は ADMIN でも取得不可であるべき")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SurveyErrorCode.ANONYMOUS_RESPONSE_FORBIDDEN);
        assertThatThrownBy(() -> surveyResponseService
                .getResponseByUser(surveyId, creatorUserId, deputyWithPermissionId))
                .as("MANAGE_SURVEYS を持つ副管理者にも匿名の壁は同じく効くべき")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SurveyErrorCode.ANONYMOUS_RESPONSE_FORBIDDEN);
    }

    // =====================================================================
    // AC-20 / AC-21: 可視性層
    // =====================================================================

    @Test
    @DisplayName("AC-20: MANAGE_SURVEYS を持つ DEPUTY_ADMIN は結果閲覧でフルアクセスを得る")
    void ac20_権限保有DEPUTY_ADMINは可視性層でフルアクセス() {
        Long adminsOnly = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        Long viewersOnly = insertSurvey(teamId, creatorUserId, "PUBLISHED", "VIEWERS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnly, deputyWithPermissionId))
                .as("ADMINS_ONLY: MANAGE_SURVEYS 保有の副管理者は閲覧可であるべき")
                .isTrue();
        assertThat(checker.canView(ReferenceType.SURVEY, viewersOnly, deputyWithPermissionId))
                .as("VIEWERS_ONLY: 上位条件（管理者相当）は results_visibility を貫通すべき")
                .isTrue();
        // 陽性対照: ADMIN は従来どおり閲覧可
        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnly, adminUserId))
                .as("陽性対照: ADMIN は従来どおり閲覧可").isTrue();
    }

    @Test
    @DisplayName("AC-21: 権限なし DEPUTY_ADMIN は結果閲覧不可")
    void ac21_権限なしDEPUTY_ADMINは結果閲覧不可() {
        Long adminsOnly = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        Long viewersOnly = insertSurvey(teamId, creatorUserId, "PUBLISHED", "VIEWERS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnly, deputyWithoutPermissionId))
                .as("ADMINS_ONLY: MANAGE_SURVEYS を持たない副管理者は閲覧不可であるべき")
                .isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, viewersOnly, deputyWithoutPermissionId))
                .as("VIEWERS_ONLY: 権限なし副管理者は閲覧不可であるべき")
                .isFalse();
    }

    @Test
    @DisplayName("AC-22: DRAFT / ARCHIVED は権限があっても貫通しない（status 軸の非貫通）")
    void ac22_status軸は権限で貫通しない() {
        Long draft = insertSurvey(teamId, creatorUserId, "DRAFT", "ALWAYS", null, null, false);
        Long archived = insertSurvey(teamId, creatorUserId, "ARCHIVED", "ALWAYS", null, null, false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, draft, deputyWithPermissionId))
                .as("DRAFT は MANAGE_SURVEYS があっても閲覧不可であるべき").isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, archived, deputyWithPermissionId))
                .as("ARCHIVED は MANAGE_SURVEYS があっても閲覧不可であるべき").isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, draft, adminUserId))
                .as("陽性対照: ADMIN でも DRAFT は貫通しない").isFalse();
        assertThat(checker.canView(ReferenceType.SURVEY, archived, adminUserId))
                .as("陽性対照: ADMIN でも ARCHIVED は貫通しない").isFalse();
    }

    @Test
    @DisplayName("AC-23: 他スコープの ADMIN は通らない")
    void ac23_他スコープのADMINは通らない() {
        Long adminsOnly = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.SURVEY, adminsOnly, otherTeamAdminId))
                .as("他チームの ADMIN が当該チームのアンケート結果を見られてはならない")
                .isFalse();
        assertThatThrownBy(() -> surveyResultService.getRespondents(adminsOnly, otherTeamAdminId))
                .as("Service 層でも他スコープの ADMIN は拒否されるべき")
                .isInstanceOf(BusinessException.class);
    }

    // =====================================================================
    // AC-24: 対象行 0 件のバッチで追加 SQL 0 本
    // =====================================================================

    @Test
    @DisplayName("AC-24: 対象行 0 件のバッチで追加 SQL が 0 本")
    void ac24_空バッチで追加SQLが0本() {
        em.flush();
        em.clear();
        SqlIntentCounter.reset();

        assertThat(checker.filterAccessible(ReferenceType.SURVEY, List.of(), deputyWithPermissionId))
                .as("空入力の結果は空集合").isEmpty();

        assertThat(SqlIntentCounter.totalCount())
                .as("対象行が 0 件なら追加軸の先読みを含め SQL を 1 本も発行してはならない"
                        + "（権限判定を行ごとに足すと、この 0 本が崩れる）")
                .isZero();
    }

    /**
     * AC-20 の下支え: 可視性層が使う<b>バルク版</b>の述語が、第一陣の<b>単票版</b>と同じ答えを返すこと。
     *
     * <p>可視性層の判定は「バルク版が権限保有スコープを返す」ことに全面的に依存するため、
     * ここが空を返すと AC-20 は静かに false になる。両版の一致を直接測っておく。</p>
     */
    @Test
    @DisplayName("AC-20-下支え: 権限バルククエリは単票版と同じ答えを返す")
    void ac20_bulk権限クエリは単票版と一致する() {
        em.flush();
        em.clear();

        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInTeam(
                deputyWithPermissionId, teamId, PERMISSION))
                .as("単票版: 権限保有 DEPUTY_ADMIN は true").isTrue();
        assertThat(userRoleRepository.findDeputyAdminPermittedTeamIds(
                deputyWithPermissionId, List.of(teamId), PERMISSION))
                .as("バルク版: 単票版が true なら当該チームを返すべき").contains(teamId);
        assertThat(userRoleRepository.findDeputyAdminPermittedTeamIds(
                deputyWithoutPermissionId, List.of(teamId), PERMISSION))
                .as("バルク版: 権限なし副管理者のスコープは返さない").isEmpty();
        assertThat(userRoleRepository.findDeputyAdminPermittedTeamIds(
                adminUserId, List.of(teamId), PERMISSION))
                .as("バルク版: ADMIN は DEPUTY_ADMIN ではないので返さない（ADMIN 経路は isScopeAdmin が担う）")
                .isEmpty();
    }

    // =====================================================================
    // AC-25 / AC-26: SQL 本数（検出器の生存証明つき）
    // =====================================================================

    /**
     * AC-25: SQL 本数の上限を測る前に、<b>計器そのものが生きている</b>ことを証明する。
     *
     * <p>{@code SqlIntentCounter} が捕捉していなければ常に 0 件と答え、どんな上限も
     * 「上限以下」で素通りする（Issue #2782 の前科）。上限アサーションの前に
     * 「非空バッチでは 1 本以上飛ぶ」を固定しておかないと、AC-26 は測っていないのに緑になる。</p>
     */
    @Test
    @DisplayName("AC-25: SqlIntentCounter は非空バッチで実際に SQL を捕捉している（計器の生存証明）")
    void ac25_sqlIntentCounterは実際に捕捉している() {
        Long surveyId = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        em.flush();
        em.clear();

        SqlIntentCounter.reset();
        checker.filterAccessible(ReferenceType.SURVEY, List.of(surveyId), deputyWithPermissionId);

        assertThat(SqlIntentCounter.totalCount())
                .as("非空バッチで 0 本と答えるなら計器が死んでおり、上限アサーションは無意味である。"
                        + "捕捉 SQL=%s", SqlIntentCounter.capturedSqls())
                .isGreaterThan(0);
    }

    /**
     * AC-26: 権限の先読みは<b>スコープ数に比例しない</b>。
     *
     * <p>1 スコープのバッチと 5 スコープのバッチで発行 SQL 本数が等しいことを直接測る。
     * 行ごと・スコープごとに {@code RoleService#resolveEffectivePermissions} を呼ぶ実装
     * （Issue #2782 で撤去した形）に戻ると、この等式が崩れて赤になる。</p>
     */
    @Test
    @DisplayName("AC-26: スコープ数を 1 → 5 に増やしても SQL 本数は増えない（比例しない）")
    void ac26_権限先読みはスコープ数に比例しない() {
        Long single = insertSurvey(teamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY", null, null, false);
        List<Long> many = new ArrayList<>();
        many.add(single);
        for (int i = 0; i < 4; i++) {
            Long extraTeamId = insertTeam();
            // 権限保有 DEPUTY_ADMIN を各チームにも置き、判定経路（権限バルク照会）を確実に踏ませる。
            grantRole(deputyWithPermissionId, "DEPUTY_ADMIN", extraTeamId, null);
            many.add(insertSurvey(extraTeamId, creatorUserId, "PUBLISHED", "ADMINS_ONLY",
                    null, null, false));
        }
        em.flush();
        em.clear();

        SqlIntentCounter.reset();
        checker.filterAccessible(ReferenceType.SURVEY, List.of(single), deputyWithPermissionId);
        int oneScope = SqlIntentCounter.totalCount();

        SqlIntentCounter.reset();
        checker.filterAccessible(ReferenceType.SURVEY, many, deputyWithPermissionId);
        int fiveScopes = SqlIntentCounter.totalCount();

        assertThat(oneScope)
                .as("AC-25 と同じ理由で、まず計器が生きていることを確認する").isGreaterThan(0);
        assertThat(fiveScopes)
                .as("スコープ数 1 → 5 で SQL 本数が増えてはならない（1 スコープ=%d 本 / 5 スコープ=%d 本・"
                        + "捕捉 SQL=%s）", oneScope, fiveScopes, SqlIntentCounter.capturedSqls())
                .isEqualTo(oneScope);
    }

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private long countNotifications() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM notifications").getSingleResult())
                .longValue();
    }

    private int remindCounterOf(Long surveyId) {
        return ((Number) em.createNativeQuery(
                "SELECT COALESCE(manual_remind_count, 0) FROM surveys WHERE id = :id")
                .setParameter("id", surveyId).getSingleResult()).intValue();
    }

    private Object lastRemindedAtOf(Long surveyId) {
        return em.createNativeQuery("SELECT last_reminded_at FROM surveys WHERE id = :id")
                .setParameter("id", surveyId).getSingleResult();
    }

    private void seedRoles() {
        insertRole("SYSTEM_ADMIN", 1);
        insertRole("ADMIN", 2);
        insertRole("DEPUTY_ADMIN", 3);
        insertRole("MEMBER", 4);
        insertRole("SUPPORTER", 5);
        insertRole("GUEST", 6);
        em.flush();
    }

    private void insertRole(String name, int priority) {
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    /** 第一陣 migration の本文をそのまま実行して MANAGE_SURVEYS のカタログ行を作る。 */
    private void seedManageSurveysFromMigration() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration が classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration 読み出し失敗: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        for (String sql : statements) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
        Number rows = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM permissions WHERE name = 'MANAGE_SURVEYS'").getSingleResult();
        assertThat(rows.intValue())
                .as("フィクスチャの自己検証: migration 由来の MANAGE_SURVEYS 行が入っていること")
                .isEqualTo(1);
    }

    private Long insertUser() {
        int n = nextSeq();
        String email = "cmp041-sv-" + n + "@example.com";
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, '調査', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "調査 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertTeam() {
        String name = "CMP041調査チーム" + nextSeq();
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                        + "NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "CMP041調査組織" + nextSeq();
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("oid", org)
                .executeUpdate();
    }

    private void grantRole(Long userId, String roleName, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "SELECT :uid, r.id, :tid, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
    }

    private Long insertPermissionGroup(Long team) {
        String name = "CMP041調査権限束" + nextSeq();
        em.createNativeQuery(
                "INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) "
                        + "VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void addPermissionToGroup(Long groupId, String permissionName) {
        em.createNativeQuery(
                "INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("gid", groupId)
                .setParameter("perm", permissionName)
                .executeUpdate();
        em.flush();
    }

    private void assignGroupToUser(Long userId, Long groupId) {
        em.createNativeQuery(
                "INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
        em.flush();
    }

    /**
     * surveys へ最小 NOT NULL 全列を直接 INSERT する。
     *
     * @param seriesId  シリーズ識別子（{@code null} 可）
     * @param anonymous 匿名アンケートか
     */
    private Long insertSurvey(Long scopeId, Long createdBy, String status, String resultsVisibility,
                              LocalDateTime expiresAt, String seriesId, boolean anonymous) {
        String title = "CMP041アンケート" + nextSeq();
        String expiresExpr = expiresAt == null ? "NULL" : "'" + expiresAt.format(DT_FMT) + "'";
        em.createNativeQuery(
                "INSERT INTO surveys (scope_type, scope_id, title, status, "
                        + "is_anonymous, allow_multiple_submissions, results_visibility, "
                        + "distribution_mode, unresponded_visibility, auto_post_to_timeline, "
                        + "manual_remind_count, response_count, target_count, "
                        + "version, created_by, series_id, expires_at, created_at, updated_at) "
                        + "VALUES ('TEAM', :scopeId, :title, :status, "
                        + ":anonymous, 0, :resultsVisibility, "
                        + "'ALL', 'CREATOR_AND_ADMIN', 0, "
                        + "0, 0, 0, "
                        + "0, :createdBy, :seriesId, " + expiresExpr + ", NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("title", title)
                .setParameter("status", status)
                .setParameter("anonymous", anonymous ? 1 : 0)
                .setParameter("resultsVisibility", resultsVisibility)
                .setParameter("createdBy", createdBy)
                .setParameter("seriesId", seriesId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM surveys WHERE title = :title")
                .setParameter("title", title).getSingleResult()).longValue();
    }

    private Long insertQuestion(Long surveyId) {
        em.createNativeQuery(
                "INSERT INTO survey_questions (survey_id, question_text, question_type, "
                        + "is_required, display_order, created_at) "
                        + "VALUES (:sid, '設問', 'FREE_TEXT', 0, 0, NOW())")
                .setParameter("sid", surveyId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM survey_questions WHERE survey_id = :sid ORDER BY id DESC LIMIT 1")
                .setParameter("sid", surveyId).getSingleResult()).longValue();
    }

    private void insertResponse(Long surveyId, Long questionId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO survey_responses (survey_id, question_id, user_id, text_response, "
                        + "created_at, updated_at) "
                        + "VALUES (:sid, :qid, :uid, '回答', NOW(), NOW())")
                .setParameter("sid", surveyId)
                .setParameter("qid", questionId)
                .setParameter("uid", userId)
                .executeUpdate();
    }
}
