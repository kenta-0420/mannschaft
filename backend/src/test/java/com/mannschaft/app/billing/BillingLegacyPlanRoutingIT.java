package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCatalogQueryService;
import com.mannschaft.app.billing.api.dto.PlanCatalogResponse;
import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260930-1931 AC09/10: 実routing DataSourceと実MySQLで接続先・TXを観測する先行試練。
 * 別の実Hikari poolをPRIMARY/REPLICAへ接続し、返した実ConnectionのprepareStatementを記録する。
 * ThreadLocalの値だけでPRIMARY読取を証明せず、応答額・実更新commit/rollbackも別TXで照合する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(BillingLegacyPlanRoutingIT.RoutingObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("1931 legacy PLANの実接続routingと中途失敗")
class BillingLegacyPlanRoutingIT extends AbstractMySqlIntegrationTest {

    private static final Instant AT = Instant.parse("2026-11-09T00:00:00Z");
    private static final int CURRENT_PRICE = 2_200;
    private static final int NEXT_PRICE = 3_630;

    @Autowired private ReadOnlyCatalogCaller caller;
    @Autowired private BillingPricePromotionService promoter;
    @Autowired private PlanRepository plans;
    @Autowired private PlanPriceBandRepository oldBands;
    @Autowired private BillingPriceVersionRepository versions;
    @Autowired private BillingPriceBandVersionRepository bands;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @TestBean(name = "utcClock", methodName = "routingClock") private Clock clock;

    private String planKey;
    private String triggerName;
    private UUID currentId;
    private UUID nextId;
    private final List<UUID> bandIds = new ArrayList<>();

    static Clock routingClock() {
        return Clock.fixed(AT, ZoneOffset.UTC);
    }

    /** 通常test基底は単一DSなので、この実接続試練だけ既存routing構成を有効化する。 */
    @DynamicPropertySource
    static void realRoutingProperties(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.replica.enabled", () -> "true");
        registry.add("spring.datasource.hikari.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.hikari.username", MYSQL::getUsername);
        registry.add("spring.datasource.hikari.password", MYSQL::getPassword);
        registry.add("app.datasource.replica.jdbc-url", MYSQL::getJdbcUrl);
        registry.add("app.datasource.replica.username", MYSQL::getUsername);
        registry.add("app.datasource.replica.password", MYSQL::getPassword);
    }

    @BeforeEach
    void 所有するAとdueBをRepositoryでcommitする() {
        planKey = "I1931R-" + UUID.randomUUID().toString().substring(0, 16);
        triggerName = "it1931_" + UUID.randomUUID().toString().replace("-", "");
        transactions.executeWithoutResult(tx -> {
            plans.saveAndFlush(PlanEntity.builder().planKey(planKey).baseMonthlyPriceJpy(1_100)
                    .displayNameKey("it1931").descriptionKey("it1931").sortOrder(0).enabled(true).build());
            currentId = savePrice(1L, BillingPriceVersionStatus.ACTIVE,
                    AT.minusSeconds(86_400L), AT, CURRENT_PRICE, 2_000L, 200L);
            nextId = savePrice(2L, BillingPriceVersionStatus.SCHEDULED, AT, null, NEXT_PRICE, 3_300L, 330L);
        });
    }

    @AfterEach
    void 所有triggerと価格だけを削除する() {
        ConnectionObservations.stop();
        DataSourceContextHolder.clear();
        if (triggerName != null) {
            jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName);
        }
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            if (currentId != null) {
                versions.deleteById(currentId);
            }
            if (nextId != null) {
                versions.deleteById(nextId);
            }
            versions.flush();
            if (planKey != null && plans.existsById(planKey)) {
                plans.deleteById(planKey);
            }
        });
    }

    /** 旧価格も外側replicaへ戻って再照会せず、parent SQLのPRIMARY snapshotに含める。 */
    @Test
    void 未公開時の旧baseと全旧帯もPRIMARY同snapshotの3SELECT以内で読む() {
        transactions.executeWithoutResult(tx -> {
            bands.deleteAllById(bandIds);
            bands.flush();
            versions.deleteAllById(List.of(currentId, nextId));
            versions.flush();
            currentId = null;
            nextId = null;
            bandIds.clear();
        });
        transactions.executeWithoutResult(tx -> oldBands.saveAllAndFlush(List.of(
                PlanPriceBandEntity.builder().planKey(planKey).scopeKind(PlanPriceBandScopeKind.TEAM)
                        .bandNo((short) 1).minMembers(1).maxMembers(20).monthlyPriceJpy(0).build(),
                PlanPriceBandEntity.builder().planKey(planKey).scopeKind(PlanPriceBandScopeKind.ORG)
                        .bandNo((short) 1).minMembers(1).monthlyPriceJpy(1100).build())));
        Trace trace = ConnectionObservations.start();
        try {
            var result = caller.readHelper(planKey);
            assertThat(result.get(new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER)).state())
                    .isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.NO_PUBLISHED_PRICE);
        } finally {
            ConnectionObservations.stop();
        }
        assertRestored(trace);
        List<StatementObservation> selects = trace.helperStatements.stream()
                .filter(event -> event.sql().stripLeading().startsWith("select")).toList();
        assertThat(selects).hasSizeBetween(1, 3).allSatisfy(event -> {
            assertThat(event.target()).isEqualTo("primaryDataSource");
            assertThat(event.active()).isTrue();
            assertThat(event.readOnly()).isFalse();
        });
        assertThat(selects).anySatisfy(event -> {
            assertThat(event.sql()).contains("billing_price_versions", "plan_price_bands", "base_monthly_price_jpy");
        });
    }

    @Test
    void 実readOnly外側から昇格をPRIMARYでcommitしPRIMARYのBを返して外側を復元する() {
        Trace trace = ConnectionObservations.start();
        PlanCatalogResponse response;
        try {
            response = caller.read();
        } finally {
            ConnectionObservations.stop();
        }

        assertThat(response.getPlans()).filteredOn(plan -> planKey.equals(plan.getPlanKey()))
                .singleElement().satisfies(plan -> assertThat(plan.getBaseMonthlyPriceJpy()).isEqualTo(NEXT_PRICE));
        assertRestored(trace);
        assertThat(trace.statements).anySatisfy(event -> {
            assertThat(event.target()).isEqualTo("replicaDataSource");
            assertThat(event.sql()).contains("from plans");
            assertThat(event.readOnly()).isTrue();
        });
        List<StatementObservation> prices = priceStatements(trace);
        assertThat(prices).isNotEmpty().allSatisfy(event -> {
            assertThat(event.target()).isEqualTo("primaryDataSource");
            assertThat(event.active()).isTrue();
            assertThat(event.readOnly()).isFalse();
        });
        assertThat(prices).anySatisfy(event -> assertThat(event.sql()).startsWith("update billing_price_versions"));
        assertThat(prices).anySatisfy(event -> assertThat(event.sql()).contains("from billing_price_band_versions"));
        assertStates(BillingPriceVersionStatus.RETIRED, BillingPriceVersionStatus.ACTIVE);
    }

    @Test
    void 子の更新後に実DBが親の昇格を拒否すると全親子をrollbackし外側を復元する() {
        installOwnedPartialFailure();
        Trace trace = ConnectionObservations.start();
        try {
            assertThatThrownBy(caller::read).isInstanceOf(DataAccessException.class);
        } finally {
            ConnectionObservations.stop();
        }

        assertRestored(trace);
        assertThat(priceStatements(trace)).anySatisfy(event -> {
            assertThat(event.sql()).startsWith("update billing_price_band_versions");
            assertThat(event.target()).isEqualTo("primaryDataSource");
            assertThat(event.active()).isTrue();
            assertThat(event.readOnly()).isFalse();
        });
        assertStates(BillingPriceVersionStatus.ACTIVE, BillingPriceVersionStatus.SCHEDULED);
    }

    /** AC10の測定器陽性対照。既存promoter単独で実DB失敗と全rollbackが成立する。 */
    @Test
    void 所有した中途失敗の測定器は既存promoterでも実際に発火し全親子を巻き戻す() {
        installOwnedPartialFailure();

        assertThatThrownBy(() -> promoter.promoteDue(
                BillingProductKind.PLAN, planKey, EntitlementScopeKind.USER, AT))
                .isInstanceOf(DataAccessException.class);

        assertStates(BillingPriceVersionStatus.ACTIVE, BillingPriceVersionStatus.SCHEDULED);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
    }

    /** legacy未接続段階でも、実readonly外側からhelperの物理pool/TX/昇格commitを証明する。 */
    @Test
    void 直接helperは実readOnly外側を中断しPRIMARYのBを返して復元する() {
        Trace trace = ConnectionObservations.start();
        Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> result;
        try { result = caller.readHelper(planKey); }
        finally { ConnectionObservations.stop(); }
        BillingPriceSelector.PlanScopeKey key = new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER);
        assertThat(result).containsOnlyKeys(key);
        assertThat(result.get(key).state()).isEqualTo(BillingPriceSelector.LegacyPlanSelectionState.SELECTED);
        assertThat(result.get(key).snapshot().bands().getFirst().amountIncludingTax()).isEqualTo((long) NEXT_PRICE);
        assertRestored(trace);
        assertThat(trace.statements).anySatisfy(event -> {
            assertThat(event.target()).isEqualTo("replicaDataSource");
            assertThat(event.sql()).contains("from plans");
            assertThat(event.readOnly()).isTrue();
        });
        assertThat(priceStatements(trace)).isNotEmpty().allSatisfy(event -> {
            assertThat(event.target()).isEqualTo("primaryDataSource");
            assertThat(event.active()).isTrue();
            assertThat(event.readOnly()).isFalse();
        });
        assertStates(BillingPriceVersionStatus.RETIRED, BillingPriceVersionStatus.ACTIVE);
    }

    @Test
    void 直接helperの昇格中途SQL失敗は全親子rollbackと実外側復元を保つ() {
        installOwnedPartialFailure();
        Trace trace = ConnectionObservations.start();
        try { assertThatThrownBy(() -> caller.readHelper(planKey)).isInstanceOf(DataAccessException.class); }
        finally { ConnectionObservations.stop(); }
        assertRestored(trace);
        assertThat(priceStatements(trace)).anySatisfy(event -> {
            assertThat(event.sql()).startsWith("update billing_price_band_versions");
            assertThat(event.target()).isEqualTo("primaryDataSource");
            assertThat(event.active()).isTrue();
            assertThat(event.readOnly()).isFalse();
        });
        assertStates(BillingPriceVersionStatus.ACTIVE, BillingPriceVersionStatus.SCHEDULED);
    }

    private void installOwnedPartialFailure() {
        // application.ymlのorder_updates=trueによりbandのbatchがparentより先にflushされる。
        // 所有keyのA/B両子が既に更新済みの時だけ親Bを拒否し、実書込後の失敗に限定する。
        jdbc.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON billing_price_versions "
                + "FOR EACH ROW BEGIN IF OLD.product_key = '" + planKey + "' "
                + "AND OLD.revision_no = 2 AND NEW.status = 'ACTIVE' "
                + "AND EXISTS (SELECT 1 FROM billing_price_band_versions WHERE product_key = '" + planKey
                + "' AND price_version_id = OLD.id AND status = 'ACTIVE') "
                + "AND EXISTS (SELECT 1 FROM billing_price_band_versions WHERE product_key = '" + planKey
                + "' AND price_version_id <> OLD.id AND status = 'RETIRED') "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'it1931-owned-failure'; END IF; END");
    }

    private void assertRestored(Trace trace) {
        assertThat(trace.returnBoundary).isNotNull();
        assertThat(trace.returnBoundary.active()).isTrue();
        assertThat(trace.returnBoundary.readOnly()).isTrue();
        assertThat(trace.returnBoundary.route()).isEqualTo(DataSourceType.REPLICA);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
    }

    private List<StatementObservation> priceStatements(Trace trace) {
        return trace.statements.stream().filter(event -> event.sql().contains("billing_price_versions")
                || event.sql().contains("billing_price_band_versions") || event.sql().contains("plan_price_bands"))
                .toList();
    }

    private UUID savePrice(long revisionNo, BillingPriceVersionStatus status, Instant from, Instant until,
                           long total, long excluding, long tax) {
        BillingPriceVersionEntity version = versions.saveAndFlush(BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("1931-route-" + revisionNo).revisionNo(revisionNo).status(status)
                .effectiveFrom(from).effectiveUntil(until).provisionAttempts(0)
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build());
        bandIds.add(bands.saveAndFlush(BillingPriceBandVersionEntity.builder()
                .productKind(BillingProductKind.PLAN).productKey(planKey).scopeKind(EntitlementScopeKind.USER)
                .priceVersionId(version.getId()).bandNo(1).minMembers(1).currency("JPY")
                .stripePriceRef("price_it1931r_" + UUID.randomUUID()).inputAmount(total)
                .taxBehavior(BillingTaxBehavior.INCLUSIVE).taxCodeSnapshot("txcd_10000000").taxMasterSnapshot("{}")
                .amountExcludingTax(excluding).taxAmount(tax).amountIncludingTax(total).taxRateBasisPoints(1_000)
                .taxNameSnapshot("消費税").includedInPrice(true).effectiveFrom(from).effectiveUntil(until)
                .status(status).creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL).build()).getId());
        return version.getId();
    }

    private void assertStates(BillingPriceVersionStatus current, BillingPriceVersionStatus next) {
        transactions.executeWithoutResult(tx -> {
            assertThat(versions.findById(currentId).orElseThrow().getStatus()).isEqualTo(current);
            assertThat(versions.findById(nextId).orElseThrow().getStatus()).isEqualTo(next);
            assertThat(bands.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(currentId))
                    .hasSize(1).allSatisfy(band -> assertThat(band.getStatus()).isEqualTo(current));
            assertThat(bands.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(nextId))
                    .hasSize(1).allSatisfy(band -> assertThat(band.getStatus()).isEqualTo(next));
        });
    }

    /** 実routing構成を変えず、物理poolが返したConnectionの観測だけを加える。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RoutingObservationConfiguration {
        @Bean
        ReadOnlyCatalogCaller readOnlyCatalogCaller(BillingCatalogQueryService service, BillingPriceSelector selector,
                                                  PlanRepository plans) {
            return new ReadOnlyCatalogCaller(service, selector, plans);
        }

        @Bean
        static BeanPostProcessor recordPhysicalPools() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof DataSource datasource
                            && ("primaryDataSource".equals(name) || "replicaDataSource".equals(name))) {
                        return new ObservedDataSource(datasource, name);
                    }
                    return bean;
                }
            };
        }
    }

    /** Spring proxy経由の実readOnly外側。価格・認可・Repositoryを差し替えない。 */
    static class ReadOnlyCatalogCaller {
        private final BillingCatalogQueryService service;
        private final BillingPriceSelector selector;
        private final PlanRepository plans;

        ReadOnlyCatalogCaller(BillingCatalogQueryService service, BillingPriceSelector selector, PlanRepository plans) {
            this.service = service;
            this.selector = selector;
            this.plans = plans;
        }

        @Transactional(readOnly = true)
        public Map<BillingPriceSelector.PlanScopeKey, BillingPriceSelector.LegacyPlanSelection> readHelper(String planKey) {
            try {
                plans.findById(planKey).orElseThrow(); // このfixtureの入力は保存keyと同じ。aliasの実証はReadITで行う。
                Trace trace = ConnectionObservations.CURRENT.get();
                int start = trace == null ? 0 : trace.statements.size();
                var result = selector.selectLegacyPlans(Set.of(new BillingPriceSelector.PlanScopeKey(planKey, EntitlementScopeKind.USER)));
                if (trace != null) {
                    trace.helperStatements = List.copyOf(trace.statements.subList(start, trace.statements.size()));
                }
                return result;
            } finally {
                Trace trace = ConnectionObservations.CURRENT.get();
                if (trace != null) {
                    trace.returnBoundary = new ReturnBoundary(TransactionSynchronizationManager.isActualTransactionActive(),
                            TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                            DataSourceContextHolder.getDataSourceType());
                }
            }
        }

        @Transactional(readOnly = true)
        public PlanCatalogResponse read() {
            try {
                return service.getCatalog();
            } finally {
                Trace trace = ConnectionObservations.CURRENT.get();
                if (trace != null) {
                    trace.returnBoundary = new ReturnBoundary(TransactionSynchronizationManager.isActualTransactionActive(),
                            TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                            DataSourceContextHolder.getDataSourceType());
                }
            }
        }
    }

    /** 読取/書込挙動は委譲し、SQL準備時の実接続先とTX状態だけを記録する。 */
    static class ObservedDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource delegate;
        private final String target;

        ObservedDataSource(DataSource delegate, String target) {
            this.delegate = delegate;
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(delegate.getConnection(username, password));
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return type.isInstance(this) || delegate.isWrapperFor(type);
        }

        @Override
        public PrintWriter getLogWriter() {
            try {
                return delegate.getLogWriter();
            } catch (SQLException exception) {
                throw new IllegalStateException("実DataSourceのログ出力先を取得できません", exception);
            }
        }

        @Override
        public void setLogWriter(PrintWriter writer) throws SQLException {
            delegate.setLogWriter(writer);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public void setLoginTimeout(int timeout) throws SQLException {
            delegate.setLoginTimeout(timeout);
        }

        private Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Trace trace = ConnectionObservations.CURRENT.get();
                        if (trace != null && "prepareStatement".equals(method.getName())
                                && arguments != null && arguments.length > 0 && arguments[0] instanceof String sql) {
                            trace.statements.add(new StatementObservation(target, sql.toLowerCase(Locale.ROOT),
                                    TransactionSynchronizationManager.isActualTransactionActive(),
                                    TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == arguments[0];
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    /** テスト実行スレッドだけの有限観測。非同期へSQLを移送しない。 */
    static class ConnectionObservations {
        private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

        static Trace start() {
            Trace trace = new Trace();
            CURRENT.set(trace);
            return trace;
        }

        static void stop() {
            CURRENT.remove();
        }
    }

    /** 観測中のSQLと呼出し復帰境界。 */
    static class Trace {
        private final List<StatementObservation> statements = new ArrayList<>();
        private List<StatementObservation> helperStatements = List.of();
        private ReturnBoundary returnBoundary;
    }

    /** 実接続が準備したSQLとその時点のTX状態。 */
    private record StatementObservation(String target, String sql, boolean active, boolean readOnly) { }
    /** 実readOnly callerのfinallyで観測した復帰境界。 */
    private record ReturnBoundary(boolean active, boolean readOnly, DataSourceType route) { }
}
