package com.mannschaft.app.billing.beta;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.billing.FeatureKeys;
import com.mannschaft.app.billing.PlanFeatureEntity;
import com.mannschaft.app.billing.PlanFeatureRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * CMP1152 billing一点: 通知DB失敗と業務rollbackを実サービスproxy・MySQLで固定する試練。
 *
 * <p>通知INSERTのみを所有CHECK制約で失敗させる。業務・通知Beanをモックしない。
 * テスト自体を@Transactionalで包まず、業務コミット後の通知を実DBから観測する。
 * 通知保存失敗2条件と非同期投入拒否1条件は修正前に失敗する見込みであり、
 * 実走前にREDと断定しない。通常配送とrollbackは既存契約の対照として固定する。</p>
 *
 * <p>投入拒否を再現するためだけに専用Importでcontextを分ける。共有contextのexecutorを
 * 停止・飽和させず、このcontextの投入境界だけを制御しAFTER_CLASSで閉じる。
 * 通常時は既存executorへそのまま渡し、DB・通知Service・業務proxyを差し替えない。</p>
 */
@DisplayName("CMP1152 ベータ特典の通知TX境界（実MySQL）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BetaGrantNotificationTransactionBoundaryIT.SubmissionFixtureConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BetaGrantNotificationTransactionBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final String BLOCK_CONSTRAINT = "chk_cmp1152_beta_notify_recipient";
    private static final List<String> FULL_KEYS = List.of(
            FeatureKeys.LEGACY_PAID_PLAN_BUNDLE,
            FeatureKeys.TEMPLATE_PREMIUM_MODULES,
            FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED,
            FeatureKeys.ADS_HIDE,
            FeatureKeys.MONETIZATION_PAYWALL,
            FeatureKeys.MONETIZATION_MEMBERSHIP_FEE);

    @Autowired private BetaGrantService service;
    @Autowired private BetaGrantRepository grants;
    @Autowired private EntitlementRepository entitlements;
    @Autowired private PlanFeatureRepository planFeatures;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MessageSource messages;
    @Autowired private UserRepository users;
    @Autowired private SubmissionGate submissionGate;
    @PersistenceContext private EntityManager entityManager;

    private boolean blockedConstraintApplied;
    private Logger logger;
    private Level previousLevel;
    private WarningAppender warnings;

    @BeforeEach
    void seedPlan() {
        logger = (Logger) LoggerFactory.getLogger("com.mannschaft.app.billing.beta");
        previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        warnings = new WarningAppender();
        warnings.start();
        logger.addAppender(warnings);
        for (String key : FULL_KEYS) {
            planFeatures.save(PlanFeatureEntity.builder().planKey("FULL").featureKey(key).build());
        }
    }

    @AfterEach
    void unblockRecipient() {
        try {
            submissionGate.reject.remove();
            if (blockedConstraintApplied) {
                jdbc.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
                blockedConstraintApplied = false;
            }
        } finally {
            logger.detachAppender(warnings);
            warnings.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void 本人通知_非同期投入拒否_業務が正常復帰してメタ権利確定とWARNを残す() {
        long subject = createRecipient();
        int rejectedBefore = submissionGate.rejectedCount.get();
        submissionGate.reject.set(true);
        try {
            assertThatCode(() -> grant(subject)).doesNotThrowAnyException();
        } finally {
            submissionGate.reject.remove();
        }
        assertThat(submissionGate.rejectedCount.get()).isEqualTo(rejectedBefore + 1);
        assertThat(submissionGate.rejection).isExactlyInstanceOf(RejectedExecutionException.class)
                .hasMessage(SubmissionGate.REJECTION_MESSAGE);
        assertGrantState(grants.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, subject, 1).orElseThrow(), false);
        assertThat(warnings.events).filteredOn(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("BETA_PERK_GRANTED")
                && event.getFormattedMessage().contains(String.valueOf(subject)))
                .hasSize(1).first().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("BETA_PERK_GRANTED", String.valueOf(subject));
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(RejectedExecutionException.class.getName());
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo(SubmissionGate.REJECTION_MESSAGE);
        });
        observeNoNotification(subject, "BETA_PERK_GRANTED");

        // 拒否解除後も同じ実executor/proxyで配送でき、通知全停止ではないこと。
        long nextSubject = createRecipient();
        grant(nextSubject);
        awaitNotification(nextSubject, "BETA_PERK_GRANTED", 1);
    }

    @ParameterizedTest(name = "取消={0}")
    @ValueSource(booleans = {false, true})
    void 本人通知_DB保存失敗_付与取消のメタと権利は確定する(boolean revoke) {
        long subject = createRecipient();
        BetaGrantEntity prior = revoke ? grant(subject) : null;
        if (revoke) {
            awaitNotification(subject, "BETA_PERK_GRANTED", 1);
        }
        String type = revoke ? "BETA_PERK_REVOKED" : "BETA_PERK_GRANTED";
        blockRecipient(subject, type);
        if (revoke) {
            assertThat(countNotifications(subject, "BETA_PERK_GRANTED")).isEqualTo(1);
        }

        // 実NotificationServiceのREQUIRED proxyが業務TXをrollback-onlyにしないこと。
        assertThatCode(() -> {
            if (revoke) {
                service.revoke(prior.getId(), BetaRevokeReason.OTHER, 9_000_001L, null);
            } else {
                grant(subject);
            }
        }).doesNotThrowAnyException();

        BetaGrantEntity persisted = grants.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, subject, 1).orElseThrow();
        assertGrantState(persisted, revoke);
        // 通知を止めただけの偽GREENを防ぎ、実失敗到達と非致命WARNを観測する。
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(warnings.events).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(type, String.valueOf(subject));
                    assertThat(event.getThrowableProxy()).isNotNull();
                }));
        observeNoNotification(subject, type);
    }

    @ParameterizedTest(name = "取消={0}, locale={1}")
    @CsvSource({"false,ja", "false,en", "true,ja", "true,en"})
    void 本人通知_個人付与取消と操作locale_本体確定後に本人だけへ配送する(boolean revoke, String language) {
        long subject = createRecipient();
        long unrelated = createRecipient();
        Locale locale = Locale.forLanguageTag(language);
        LocaleContext previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            LocaleContextHolder.setLocale(locale);
            BetaGrantEntity created = grant(subject);
            awaitNotification(subject, "BETA_PERK_GRANTED", 1);
            if (revoke) {
                service.revoke(created.getId(), BetaRevokeReason.OTHER, 9_000_001L, null);
            }
        } finally {
            LocaleContextHolder.setLocaleContext(previousLocale);
        }
        String type = revoke ? "BETA_PERK_REVOKED" : "BETA_PERK_GRANTED";
        awaitNotification(subject, type, 1);
        assertGrantState(grants.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, subject, 1).orElseThrow(), revoke);
        assertThat(countNotifications(unrelated, type)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT scope_type FROM notifications WHERE user_id = ? AND notification_type = ?",
                String.class, subject, type)).isEqualTo("PERSONAL");
        assertThat(jdbc.queryForObject(
                "SELECT scope_id FROM notifications WHERE user_id = ? AND notification_type = ?",
                Long.class, subject, type)).isEqualTo(subject);
        String titleKey = revoke ? "notification.beta_perk.revoked.title" : "notification.beta_perk.granted.title";
        String bodyKey = revoke ? "notification.beta_perk.revoked.body" : "notification.beta_perk.granted.body.individual";
        assertThat(jdbc.queryForObject(
                "SELECT title FROM notifications WHERE user_id = ? AND notification_type = ?",
                String.class, subject, type)).isEqualTo(messages.getMessage(titleKey, null, locale));
        assertThat(jdbc.queryForObject(
                "SELECT body FROM notifications WHERE user_id = ? AND notification_type = ?",
                String.class, subject, type)).isEqualTo(messages.getMessage(bodyKey, null, locale));
        assertThat(jdbc.queryForObject(
                "SELECT priority FROM notifications WHERE user_id = ? AND notification_type = ?",
                String.class, subject, type)).isEqualTo(revoke ? "HIGH" : "NORMAL");
    }

    @ParameterizedTest(name = "取消={0}")
    @ValueSource(booleans = {false, true})
    void 本人通知_外側業務TXがロールバック_通知と本体の変更を残さない(boolean revoke) {
        long subject = createRecipient();
        BetaGrantEntity prior = revoke ? grant(subject) : null;
        if (revoke) {
            awaitNotification(subject, "BETA_PERK_GRANTED", 1);
        }
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            if (revoke) {
                service.revoke(prior.getId(), BetaRevokeReason.OTHER, 9_000_001L, null);
            } else {
                grant(subject);
            }
            throw new IllegalStateException("CMP1152 業務rollback試練");
        })).isInstanceOf(IllegalStateException.class);

        if (revoke) {
            assertThat(grants.findById(prior.getId()).orElseThrow().isRevoked()).isFalse();
            assertThat(entitlements.findBySourceKindAndSourceRefIdAndRevokedAtIsNull(
                    EntitlementSourceKind.BETA_GRANT, prior.getId())).hasSize(FULL_KEYS.size());
        } else {
            assertThat(grants.findByScopeKindAndScopeIdAndBetaPhase(
                    EntitlementScopeKind.USER, subject, 1)).isEmpty();
        }
        observeNoNotification(subject, revoke ? "BETA_PERK_REVOKED" : "BETA_PERK_GRANTED");
    }

    private BetaGrantEntity grant(long subject) {
        return service.grantBetaPerk(GrantKind.INDIVIDUAL, 1, EntitlementScopeKind.USER,
                subject, null, true, 9_000_001L);
    }

    private long createRecipient() {
        // ddl-auto=createのFK欠如に依存せず、受信者を実Repositoryからコミットする。
        return transactions.execute(tx -> users.save(BetaNotificationFixture.recipient()).getId());
    }

    private void assertGrantState(BetaGrantEntity grant, boolean revoked) {
        assertThat(grant.isRevoked()).isEqualTo(revoked);
        List<EntitlementEntity> rows = entityManager.createQuery(
                        "SELECT e FROM EntitlementEntity e WHERE e.sourceKind = :kind"
                                + " AND e.sourceRefId = :grantId", EntitlementEntity.class)
                .setParameter("kind", EntitlementSourceKind.BETA_GRANT)
                .setParameter("grantId", grant.getId()).getResultList();
        assertThat(rows).hasSize(FULL_KEYS.size());
        assertThat(rows).extracting(EntitlementEntity::getFeatureKey)
                .containsExactlyInAnyOrderElementsOf(FULL_KEYS);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getScopeKind()).isEqualTo(EntitlementScopeKind.USER);
            assertThat(row.getScopeId()).isEqualTo(grant.getScopeId());
            assertThat(row.getValidUntil()).isNull();
            if (revoked) {
                assertThat(row.getRevokedAt()).isNotNull();
            } else {
                assertThat(row.getRevokedAt()).isNull();
            }
        });
    }

    private void blockRecipient(long subject, String type) {
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (user_id <> " + subject + " OR notification_type <> '" + type + "')");
        blockedConstraintApplied = true;
    }

    private long countNotifications(long subject, String type) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications"
                + " WHERE user_id = ? AND notification_type = ?", Long.class, subject, type);
    }

    private void awaitNotification(long subject, String type, long expected) {
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(subject, type)).isEqualTo(expected));
    }

    private void observeNoNotification(long subject, String type) {
        await().during(Duration.ofSeconds(2)).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countNotifications(subject, type)).isZero());
    }

    /** 非同期workerからのログも安全に保持する、この試練だけの観測器。 */
    private static final class WarningAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }

    /** 当該試練だけの有効ユーザーfixture。INSERT SQLで必須列を迂回しない。 */
    private static final class BetaNotificationFixture {
        private static UserEntity recipient() {
            return UserEntity.builder()
                    .email("cmp1152-beta-" + UUID.randomUUID() + "@example.com")
                    .lastName("通知境界試練")
                    .firstName("太郎")
                    .displayName("ベータ通知試練ユーザー")
                    .isSearchable(false)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .contactApprovalRequired(false)
                    .build();
        }
    }

    /** 当該クラス専用contextだけで、executor投入境界を制御する。業務Beanは実物のまま。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class SubmissionFixtureConfiguration {
        @Bean
        static SubmissionGate cmp1152SubmissionGate() {
            return new SubmissionGate();
        }

        @Bean
        static BeanPostProcessor cmp1152EventPoolBoundary(SubmissionGate gate) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if ("event-pool".equals(name)) {
                        return new SubmissionBoundary((AsyncTaskExecutor) bean, gate);
                    }
                    return bean;
                }
            };
        }
    }

    private static final class SubmissionGate {
        private static final String REJECTION_MESSAGE = "CMP1152 専用fixtureの投入拒否";
        private final ThreadLocal<Boolean> reject = ThreadLocal.withInitial(() -> false);
        private final AtomicInteger rejectedCount = new AtomicInteger();
        private RejectedExecutionException rejection;

        private void check() {
            // 同期AFTER_COMMIT listenerからの当該sender投入だけを拒否する。
            // 名前不一致・別workerでは拒否せず、exact1の検査を失敗させる。
            if (reject.get() && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().equals("com.mannschaft.app.billing.beta.BetaGrantNotificationListener")
                            && frame.getMethodName().equals("onBetaGrantNotification")))) {
                rejectedCount.incrementAndGet();
                rejection = new RejectedExecutionException(REJECTION_MESSAGE);
                throw rejection;
            }
        }
    }

    /** 拒否しない場合は既存event-poolそのものへ渡す。新thread/poolは作らない。 */
    private static final class SubmissionBoundary implements AsyncTaskExecutor, DisposableBean {
        private final AsyncTaskExecutor delegate;
        private final SubmissionGate gate;

        private SubmissionBoundary(AsyncTaskExecutor delegate, SubmissionGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public void execute(Runnable task) {
            gate.check();
            delegate.execute(task);
        }

        @Override
        public Future<?> submit(Runnable task) {
            gate.check();
            return delegate.submit(task);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            gate.check();
            return delegate.submit(task);
        }

        @Override
        public void destroy() throws Exception {
            if (delegate instanceof DisposableBean disposable) {
                disposable.destroy();
            }
        }
    }
}
