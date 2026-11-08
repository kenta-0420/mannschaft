package com.mannschaft.app.survey;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import com.mannschaft.app.support.perf.CountingDataSource;
import com.mannschaft.app.support.perf.SqlStatementCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.service.SurveyRemindService;
import com.mannschaft.app.survey.service.SurveyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Issue #2997 / CMP-260827-1152 G5（survey）— アンケートの督促（remind）・締切延長（extendDeadline）の
 * 付随通知の業務TX分離を実 MySQL で検証する。
 *
 * <h2>現行実装で落ちる理由（試練 red）</h2>
 * <p>現行は業務TXの内側で {@code NotificationHelper#notifyAllPreAuthorizedLocalized} を呼ぶ。この経路の
 * チャンク INSERT は {@code REQUIRES_NEW} の独立TXで<b>業務コミットより前に確定</b>するため、
 * 業務TXがコミット直前に失敗（rollback）しても通知行だけが残る（AC-A が red）。</p>
 *
 * <h2>観測方法と限界</h2>
 * <ul>
 *   <li>AC-A: 業務TX内で操作したあと {@code beforeCommit} で例外を投げて強制 rollback させ、
 *       event-pool が空になる（キューも実行中も 0）まで待ってから通知行 0 件を確認する。
 *       sleep だけで 0 件判定はしない。</li>
 *   <li>AC-B/B2: {@link NotificationBulkFanoutService#insertAndDispatchChunk} を {@code @MockitoSpyBean} で
 *       spy し、チャンク単位で {@link DataIntegrityViolationException} を注入する（実 MySQL の INSERT 自体を
 *       失敗させる形ではない）。注入点への到達は spy の呼び出し回数を await して証拠とする。</li>
 *   <li>性能: {@code NotificationBulkFanoutService} の INSERT は {@code JdbcTemplate} のため Hibernate 統計では
 *       数えられない。DataSource を {@link CountingDataSource} で包み {@code insert into notifications} の
 *       実行文数を数え、通知行数と併せて検証する。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} を付けない（付けると commit が起きず AFTER_COMMIT が発火しない）。</p>
 */
@Import(SurveyReminderNotificationTransactionIT.CountingDsConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G5 survey 督促・締切延長通知のTX分離（実DB）")
class SurveyReminderNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String REMINDER_TYPE = "SURVEY_RESPONSE_REMINDER";
    private static final String INSERT_NOTIFICATIONS = "insert-notifications";

    @Autowired private SurveyRemindService remindService;
    @Autowired private SurveyService surveyService;
    @Autowired private SurveyRepository surveyRepository;
    @Autowired private SurveyResponseRepository responseRepository;
    @Autowired private TransactionTemplate txTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SqlStatementCounter statementCounter;
    @Autowired @Qualifier("event-pool") private java.util.concurrent.Executor eventPool;

    @PersistenceContext private EntityManager em;

    /** チャンク単位の失敗注入・到達観測用 spy（実 Bean のまま、必要なときだけ例外を注入する）。 */
    @MockitoSpyBean private NotificationBulkFanoutService bulkFanoutService;

    @BeforeEach
    void 前テストの非同期処理が完了してから始める() {
        awaitEventPoolIdle();
        statementCounter.register(INSERT_NOTIFICATIONS, s -> s.startsWith("insert into notifications ("));
        // JDBC/DataSource 層の失敗注入: 武装中、通知バルク INSERT の「先頭の 1 文」だけを実行前に SQLException で失敗させる。
        // 試行された各 INSERT の行数（= チャンク人数）を記録するので、注入点（チャンクTX内の JDBC）への到達の証拠になる。
        failFirstInsertArmed.set(false);
        attemptedInsertRows.clear();
        statementCounter.register("g5-fail-first-insert", sql -> {
            if (!sql.startsWith("insert into notifications (")) {
                return false;
            }
            int rows = (sql.length() - sql.replace("utc_timestamp()", "").length()) / "utc_timestamp()".length();
            attemptedInsertRows.add(rows);
            if (failFirstInsertArmed.compareAndSet(true, false)) {
                sneakyThrow(new java.sql.SQLException("模擬 INSERT 失敗（先頭チャンクの JDBC 段・G5検証用）"));
            }
            return false;
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean failFirstInsertArmed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.List<Integer> attemptedInsertRows =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    // ═════════════════════════════════════════════════════════════════════
    // remind
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("remind AC-A: コミット直前に強制 rollback されたら通知は1件も作られず、カウンタも進まない")
    void remind_ロールバック時は通知が作られない() {
        Fixture f = fixture("r-a", 3, "ja");

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            remindService.remind(f.surveyId(), f.creatorId());
            failAtBeforeCommit();
        })).isInstanceOf(RuntimeException.class);

        awaitEventPoolIdle();
        assertThat(notificationUserIds(f.surveyId())).as("rollback した以上 通知行は 0 件").isEmpty();
        assertThat(reload(f.surveyId()).getManualRemindCount()).as("督促カウンタも巻き戻っている").isZero();
    }

    @Test
    @DisplayName("remind AC-A2: コミットされたら未回答の全員に通知行が作られる（回答済みは除外）")
    void remind_コミット後に全員へ届く() {
        Fixture f = fixture("r-a2", 4, "ja");
        answer(f.surveyId(), f.recipientIds().get(0));

        RemindResponse response = remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationUsers(f.surveyId(), f.recipientIds().subList(1, 4));
        assertThat(response.remindedCount()).as("件数は対象人数（回答済みを除いた3名）").isEqualTo(3);
    }

    @Test
    @DisplayName("remind M6: 応答の文言は「送信を受け付けました」で、件数は対象人数のまま")
    void remind_応答文言は受付() {
        Fixture f = fixture("r-m6", 2, "ja");

        RemindResponse response = remindService.remind(f.surveyId(), f.creatorId());

        assertThat(response.remindedCount()).isEqualTo(2);
        assertThat(response.message()).isEqualTo("2名の未回答メンバーにリマインドの送信を受け付けました。");
    }

    @Test
    @DisplayName("remind AC-B: 通知の永続化が失敗しても督促カウンタ・最終督促時刻は残り、呼び出しは正常に返る")
    void remind_通知失敗でもカウンタは残る() {
        Fixture f = fixture("r-b", 3, "ja");
        failEveryChunk();

        RemindResponse response = remindService.remind(f.surveyId(), f.creatorId());

        awaitChunkCalls(1);
        SurveyEntity reloaded = reload(f.surveyId());
        assertThat(reloaded.getManualRemindCount()).isEqualTo(1);
        assertThat(reloaded.getLastRemindedAt()).isNotNull();
        assertThat(response.remindedCount()).isEqualTo(3);
        awaitEventPoolIdle();
        assertThat(notificationUserIds(f.surveyId())).isEmpty();
    }

    @Test
    @DisplayName("remind 同一 locale 501人: 先頭チャンクの INSERT が JDBC 層で失敗しても、後続の1人にだけ届く（チャンク単位分離）")
    void remind_先頭チャンク失敗でも後続チャンクは届く() {
        Fixture f = fixture("r-501", 501, "ja");
        failFirstInsertArmed.set(true);

        remindService.remind(f.surveyId(), f.creatorId());

        // 注入点への到達の証拠: チャンクTX内の JDBC に 500人・1人の 2 文が試行された（先頭は失敗、後続は成功）。
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(attemptedInsertRows).containsExactly(500, 1));
        awaitEventPoolIdle();
        List<List<Long>> chunks = observedChunks();
        assertThat(chunks).hasSize(2);
        List<Long> failedChunk = chunks.get(0);
        List<Long> survivingChunk = chunks.get(1);
        assertThat(failedChunk).hasSize(500);
        assertThat(survivingChunk).hasSize(1);
        assertThat(java.util.stream.Stream.concat(failedChunk.stream(), survivingChunk.stream()).toList())
                .as("観測したチャンク引数の和が受信者全員").containsExactlyInAnyOrderElementsOf(f.recipientIds());
        // 通知行は後続チャンクの1人ちょうど。先頭チャンクの500人には1件も無い。
        assertThat(notificationUserIds(f.surveyId())).containsExactlyElementsOf(survivingChunk);
    }

    @Test
    @DisplayName("remind 別 locale 継続: 先頭 locale の INSERT が失敗しても後続 locale の全員にだけ届く")
    void remind_別localeは継続する() {
        List<Long> ja = new ArrayList<>();
        List<Long> en = new ArrayList<>();
        Fixture f = fixtureTwoLocales("r-loc", 2, 3, ja, en);
        failFirstInsertArmed.set(true);

        remindService.remind(f.surveyId(), f.creatorId());

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(attemptedInsertRows).hasSize(2));
        awaitEventPoolIdle();
        List<List<Long>> chunks = observedChunks();
        assertThat(chunks).hasSize(2);
        // チャンクは locale ごとに分かれ、各 locale の集合どおりである。
        assertThat(chunks).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrderElementsOf(ja));
        assertThat(chunks).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrderElementsOf(en));
        // 先頭（失敗）の locale 集合には 0 件、後続 locale の集合ちょうどに届く。
        assertThat(notificationUserIds(f.surveyId()))
                .containsExactlyInAnyOrderElementsOf(chunks.get(1));
        assertThat(notificationUserIds(f.surveyId())).doesNotContainAnyElementsOf(chunks.get(0));
    }

    @Test
    @DisplayName("remind locale 別チャンク: 失敗が無ければ ja / en それぞれの集合どおりに 2 チャンクで全員に届く")
    void remind_locale別チャンクは各localeの集合どおり() {
        List<Long> ja = new ArrayList<>();
        List<Long> en = new ArrayList<>();
        Fixture f = fixtureTwoLocales("r-loc2", 2, 3, ja, en);

        remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationUsers(f.surveyId(), f.recipientIds());
        List<List<Long>> chunks = observedChunks();
        assertThat(chunks).hasSize(2);
        assertThat(chunks).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrderElementsOf(ja));
        assertThat(chunks).anySatisfy(c -> assertThat(c).containsExactlyInAnyOrderElementsOf(en));
    }

    @Test
    @DisplayName("remind 結果閲覧権なし・回答権ありの一般メンバーにも届く（ADMINS_ONLY アンケート）")
    void remind_結果閲覧権なしにも届く() {
        Long teamId = txTemplate.execute(s -> insertTeam("g5vis"));
        Long creator = insertUsers("g5vis-c", 1, "ja").get(0);
        Long member = insertUsers("g5vis-m", 1, "ja").get(0);
        txTemplate.executeWithoutResult(s ->
                MembershipTestHelper.insertMembership(em, member, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        Long surveyId = txTemplate.execute(s -> surveyRepository.save(SurveyEntity.builder()
                .scopeType("TEAM").scopeId(teamId).title("G5 結果閲覧権なし")
                .status(SurveyStatus.PUBLISHED).distributionMode(DistributionMode.ALL)
                .resultsVisibility(ResultsVisibility.ADMINS_ONLY)
                .createdBy(creator).build()).getId());

        remindService.remind(surveyId, creator);

        awaitNotificationUsers(surveyId, List.of(member));
    }

    @Test
    @DisplayName("remind 母集団外・別テナント: 名簿外ユーザー・別チームの所属者には届かない")
    void remind_母集団外と別テナントには届かない() {
        Fixture f = fixture("r-out", 2, "ja");
        Long outsider = insertUsers("g5out-o", 1, "ja").get(0);   // 名簿に載っていない
        Long otherTeam = txTemplate.execute(s -> insertTeam("g5oth"));
        Long otherTenantMember = insertUsers("g5out-t", 1, "ja").get(0);
        txTemplate.executeWithoutResult(s ->
                MembershipTestHelper.insertMembership(em, otherTenantMember, ScopeType.TEAM, otherTeam, RoleKind.MEMBER));

        remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationUsers(f.surveyId(), f.recipientIds());
        awaitEventPoolIdle();
        assertThat(notificationUserIds(f.surveyId())).doesNotContain(outsider, otherTenantMember);
    }

    @Test
    @DisplayName("remind 0人: 未回答者がいなければ配送を呼ばない")
    void remind_0人は配送を呼ばない() {
        Fixture f = fixture("r-zero", 2, "ja");
        f.recipientIds().forEach(uid -> answer(f.surveyId(), uid));

        RemindResponse response = remindService.remind(f.surveyId(), f.creatorId());

        awaitEventPoolIdle();
        assertThat(response.remindedCount()).isZero();
        assertThat(chunkCalls()).as("配送（チャンク INSERT）は一度も呼ばれない").isZero();
        assertThat(notificationUserIds(f.surveyId())).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // extendDeadline
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extend AC-A: コミット直前に強制 rollback されたら通知は作られず、締切も延びない")
    void extend_ロールバック時は通知が作られない() {
        Fixture f = fixture("e-a", 3, "ja");
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(30);

        assertThatThrownBy(() -> txTemplate.executeWithoutResult(tx -> {
            surveyService.extendDeadline("TEAM", f.teamId(), f.surveyId(), newDeadline, f.creatorId());
            failAtBeforeCommit();
        })).isInstanceOf(RuntimeException.class);

        awaitEventPoolIdle();
        assertThat(notificationUserIds(f.surveyId())).isEmpty();
        assertThat(reload(f.surveyId()).getExpiresAt()).isNotEqualTo(newDeadline);
    }

    @Test
    @DisplayName("extend AC-A2: コミットされたら母集団の全員に通知行が作られ、締切が延びている")
    void extend_コミット後に全員へ届く() {
        Fixture f = fixture("e-a2", 3, "ja");
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(30).withNano(0);

        surveyService.extendDeadline("TEAM", f.teamId(), f.surveyId(), newDeadline, f.creatorId());

        awaitNotificationUsers(f.surveyId(), f.recipientIds());
        assertThat(reload(f.surveyId()).getExpiresAt()).isEqualTo(newDeadline);
    }

    @Test
    @DisplayName("extend AC-B: 通知の永続化が失敗しても延長後の締切は残り、呼び出しは正常に返る")
    void extend_通知失敗でも締切は残る() {
        Fixture f = fixture("e-b", 3, "ja");
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(30).withNano(0);
        failEveryChunk();

        surveyService.extendDeadline("TEAM", f.teamId(), f.surveyId(), newDeadline, f.creatorId());

        awaitChunkCalls(1);
        assertThat(reload(f.surveyId()).getExpiresAt()).isEqualTo(newDeadline);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 性能: INSERT 文数 = Σ_locale ceil(Nₗ/500)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("性能: 500人（同一locale）は INSERT 1文・通知500行")
    void 性能_500人は1文() {
        Fixture f = fixture("p-500", 500, "ja");
        awaitEventPoolIdle();
        statementCounter.reset();

        remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationCount(f.surveyId(), 500);
        assertThat(statementCounter.count(INSERT_NOTIFICATIONS)).isEqualTo(1);
    }

    @Test
    @DisplayName("性能: 501人（同一locale）は INSERT 2文・通知501行")
    void 性能_501人は2文() {
        Fixture f = fixture("p-501", 501, "ja");
        awaitEventPoolIdle();
        statementCounter.reset();

        remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationCount(f.surveyId(), 501);
        assertThat(statementCounter.count(INSERT_NOTIFICATIONS)).isEqualTo(2);
    }

    @Test
    @DisplayName("性能: ja300人 + en300人は locale ごとに1文ずつ計2文・通知600行")
    void 性能_ロケール別に1文ずつ() {
        List<Long> ja = new ArrayList<>();
        List<Long> en = new ArrayList<>();
        Fixture f = fixtureTwoLocales("p-loc", 300, 300, ja, en);
        awaitEventPoolIdle();
        statementCounter.reset();

        remindService.remind(f.surveyId(), f.creatorId());

        awaitNotificationCount(f.surveyId(), 600);
        assertThat(statementCounter.count(INSERT_NOTIFICATIONS)).isEqualTo(2);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private record Fixture(Long teamId, Long surveyId, Long creatorId, List<Long> recipientIds) {
    }

    /** 名簿指定（TARGETED）の公開済みアンケートと、同一 locale の受信者 n 名を用意する。 */
    private Fixture fixture(String tag, int n, String locale) {
        Long teamId = txTemplate.execute(s -> insertTeam("g5" + tag));
        Long creator = insertUsers("g5c-" + tag, 1, "ja").get(0);
        List<Long> recipients = insertUsers("g5m-" + tag, n, locale);
        Long surveyId = newTargetedSurvey(teamId, creator, recipients);
        return new Fixture(teamId, surveyId, creator, recipients);
    }

    private Fixture fixtureTwoLocales(String tag, int jaCount, int enCount, List<Long> jaOut, List<Long> enOut) {
        Long teamId = txTemplate.execute(s -> insertTeam("g5" + tag));
        Long creator = insertUsers("g5c-" + tag, 1, "ja").get(0);
        jaOut.addAll(insertUsers("g5j-" + tag, jaCount, "ja"));
        enOut.addAll(insertUsers("g5e-" + tag, enCount, "en"));
        List<Long> all = new ArrayList<>(jaOut);
        all.addAll(enOut);
        Long surveyId = newTargetedSurvey(teamId, creator, all);
        return new Fixture(teamId, surveyId, creator, all);
    }

    private Long newTargetedSurvey(Long teamId, Long creator, List<Long> targets) {
        Long surveyId = txTemplate.execute(s -> surveyRepository.save(SurveyEntity.builder()
                .scopeType("TEAM").scopeId(teamId).title("G5 アンケート")
                .status(SurveyStatus.PUBLISHED).distributionMode(DistributionMode.TARGETED)
                .expiresAt(LocalDateTime.now().plusDays(3))
                .createdBy(creator).build()).getId());
        jdbc.batchUpdate("INSERT INTO survey_targets (survey_id, user_id, created_at) VALUES (?, ?, NOW())",
                targets, 500, (ps, uid) -> {
                    ps.setLong(1, surveyId);
                    ps.setLong(2, uid);
                });
        return surveyId;
    }

    private void answer(Long surveyId, Long userId) {
        txTemplate.executeWithoutResult(s -> responseRepository.save(SurveyResponseEntity.builder()
                .surveyId(surveyId).questionId(1L).userId(userId).textResponse("はい").build()));
    }

    private SurveyEntity reload(Long surveyId) {
        return txTemplate.execute(s -> surveyRepository.findById(surveyId).orElseThrow());
    }

    private void failAtBeforeCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                throw new IllegalStateException("コミット直前の強制失敗（AC-A検証用）");
            }
        });
    }

    private void failEveryChunk() {
        invokeAnyChunk(Mockito.doThrow(new DataIntegrityViolationException("模擬通知永続化失敗（AC-B検証用）"))
                .when(bulkFanoutService));
    }

    /** {@code insertAndDispatchChunk(List<Long>, ...)}（12引数版）を any マッチャで呼ぶ（stubbing 用）。 */
    private static void invokeAnyChunk(NotificationBulkFanoutService stub) {
        stub.insertAndDispatchChunk(ArgumentMatchers.<Long>anyList(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** spy が観測した {@code insertAndDispatchChunk(List<Long>, ...)}（12引数版）のチャンク引数（受信者 ID）を呼び出し順に返す。 */
    @SuppressWarnings("unchecked")
    private List<List<Long>> observedChunks() {
        return Mockito.mockingDetails(bulkFanoutService).getInvocations().stream()
                .filter(i -> "insertAndDispatchChunk".equals(i.getMethod().getName()))
                .filter(i -> i.getArguments().length == 12)
                .map(i -> List.copyOf((List<Long>) i.getArgument(0)))
                .toList();
    }

    private long chunkCalls() {
        return Mockito.mockingDetails(bulkFanoutService).getInvocations().stream()
                .filter(i -> "insertAndDispatchChunk".equals(i.getMethod().getName()))
                .filter(i -> i.getArguments().length == 12)
                .count();
    }

    private void awaitChunkCalls(long expected) {
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(chunkCalls()).as("失敗注入点（チャンク INSERT）へ到達した回数").isEqualTo(expected));
    }

    private List<Long> notificationUserIds(Long surveyId) {
        return jdbc.queryForList(
                "SELECT user_id FROM notifications WHERE notification_type = ? AND source_type = 'SURVEY' "
                        + "AND source_id = ?", Long.class, REMINDER_TYPE, surveyId);
    }

    private void awaitNotificationCount(Long surveyId, int expected) {
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationUserIds(surveyId)).hasSize(expected));
    }

    private void awaitNotificationUsers(Long surveyId, List<Long> expectedUsers) {
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationUserIds(surveyId)).containsExactlyInAnyOrderElementsOf(expectedUsers));
    }

    /** event-pool の実行中・待機中タスクがなくなるまで待つ（AC-A の「非同期完了」の待機印）。 */
    private void awaitEventPoolIdle() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) eventPool;
        await().atMost(60, TimeUnit.SECONDS).until(() ->
                pool.getThreadPoolExecutor().getQueue().isEmpty() && pool.getActiveCount() == 0);
    }

    private List<Long> insertUsers(String tag, int n, String locale) {
        String prefix = tag + "-" + System.nanoTime() + "-";
        List<String> emails = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            emails.add(prefix + i + "@example.com");
        }
        jdbc.batchUpdate(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'G5', 'テスト', 'G5 テスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "?, 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                emails, 500, (ps, email) -> {
                    ps.setString(1, email);
                    ps.setString(2, locale);
                });
        return jdbc.queryForList("SELECT id FROM users WHERE email LIKE ? ORDER BY id", Long.class, prefix + "%");
    }

    private Long insertTeam(String slugBase) {
        String slug = (slugBase + Long.toString(System.nanoTime(), 36)).toLowerCase();
        if (slug.length() > 30) {
            slug = slug.substring(slug.length() - 30);
        }
        final String finalSlug = slug;
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", "G5 " + finalSlug)
                .setParameter("slug", finalSlug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", finalSlug)
                .getSingleResult()).longValue();
    }

    /** データソース層の SQL 文数計測用構成（JdbcTemplate の多値 INSERT も数えるため Hibernate 統計は使わない）。 */
    @TestConfiguration
    static class CountingDsConfig {
        static final SqlStatementCounter COUNTER = new SqlStatementCounter();

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return COUNTER;
        }

        @Bean
        static BeanPostProcessor g5CountingDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds && !(bean instanceof CountingDataSource)) {
                        return new CountingDataSource(ds, COUNTER);
                    }
                    return bean;
                }
            };
        }
    }
}
