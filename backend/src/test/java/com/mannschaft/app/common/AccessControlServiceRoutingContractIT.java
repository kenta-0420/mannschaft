package com.mannschaft.app.common;

import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.config.RoutingDataSource;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC6: interface 化前後の実 Spring AOP / MySQL / DataSource 選択を固定する回帰試験。
 *
 * <p>現行の class レベル readOnly TX でも最初から green になる characterization test であり、
 * 新 interface の未実装を示す red 試練とは区別する。2つの実 Hikari pool は同一 MySQL を指す。
 * 本番 RoutingDataSource の選択先を観測する試験であり、物理レプリケーションは検査しない。
 * 専用の routing 設定と観測 wrapper はクラス終了時に context ごと破棄する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(AccessControlServiceRoutingContractIT.RoutingObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.datasource.replica.enabled=true",
        "spring.datasource.hikari.jdbc-url=${spring.datasource.url}",
        "spring.datasource.hikari.username=${spring.datasource.username}",
        "spring.datasource.hikari.password=${spring.datasource.password}",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "app.datasource.replica.jdbc-url=${spring.datasource.url}",
        "app.datasource.replica.username=${spring.datasource.username}",
        "app.datasource.replica.password=${spring.datasource.password}",
        "app.datasource.replica.maximum-pool-size=2",
        "app.datasource.replica.minimum-idle=0"
})
class AccessControlServiceRoutingContractIT extends AbstractMySqlIntegrationTest {

    private static final long ABSENT_ID = Long.MIN_VALUE;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private OuterCalls outerCalls;

    @Autowired
    private PoolObserver observer;

    @Autowired
    @Qualifier("routingDataSource")
    private DataSource routingDataSource;

    @BeforeEach
    void 観測開始時にテストTXとルーティング残留がない() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
        assertThat(AopUtils.isAopProxy(accessControlService)).isTrue();
        assertThat(AopUtils.isAopProxy(outerCalls)).isTrue();
        assertThat(routingDataSource).isInstanceOf(RoutingDataSource.class);
        observer.begin();
    }

    @AfterEach
    void 観測とThreadLocalを片付ける() {
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
        } finally {
            observer.end();
            DataSourceContextHolder.clear();
        }
    }

    @Test
    void トップレベルのclassReadOnlyはPRIMARYを使い実commitする() {
        assertThat(accessControlService.isMember(ABSENT_ID, ABSENT_ID, "TEAM")).isFalse();

        assertRouteAndCompletion(DataSourceType.PRIMARY, null, true, "commit");
    }

    @Test
    void 外側書込TXのPRIMARYとreadOnly属性を保持して実commitする() {
        outerCalls.write(false);

        assertRouteAndCompletion(DataSourceType.PRIMARY, DataSourceType.PRIMARY, false, "commit");
    }

    @Test
    void 外側読取TXのREPLICAとreadOnly属性を保持して実commitする() {
        outerCalls.read(false);

        assertRouteAndCompletion(DataSourceType.REPLICA, DataSourceType.REPLICA, true, "commit");
    }

    @Test
    void トップレベル認可例外はCOMMON002で実rollbackしルーティングを残さない() {
        assertThatThrownBy(() -> accessControlService.checkMembership(ABSENT_ID, ABSENT_ID, "TEAM"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));

        assertRouteAndCompletion(DataSourceType.PRIMARY, null, true, "rollback");
    }

    @Test
    void 認可例外を捕捉しても外側PRIMARYへ復帰しrollbackOnlyを実rollbackする() {
        assertThatThrownBy(() -> outerCalls.write(true)).isExactlyInstanceOf(UnexpectedRollbackException.class);

        assertRouteAndCompletion(DataSourceType.PRIMARY, DataSourceType.PRIMARY, false, "rollback");
    }

    @Test
    void 認可例外を捕捉しても外側REPLICAへ復帰しrollbackOnlyを実rollbackする() {
        assertThatThrownBy(() -> outerCalls.read(true)).isExactlyInstanceOf(UnexpectedRollbackException.class);

        assertRouteAndCompletion(DataSourceType.REPLICA, DataSourceType.REPLICA, true, "rollback");
    }

    private void assertRouteAndCompletion(DataSourceType target, DataSourceType holder,
                                         boolean readOnly, String completion) {
        List<Observation> events = observer.events();
        assertThat(events).filteredOn(event -> event.operation().equals("connection"))
                .isNotEmpty().allSatisfy(event -> assertThat(event.target()).isEqualTo(target));
        assertThat(events).filteredOn(event -> event.operation().equals("query"))
                .isNotEmpty().allSatisfy(event -> {
                    assertThat(event.target()).isEqualTo(target);
                    assertThat(event.holder()).isEqualTo(holder);
                    assertThat(event.transactionActive()).isTrue();
                    assertThat(event.readOnly()).isEqualTo(readOnly);
                });
        assertThat(events).filteredOn(event -> event.operation().equals(completion))
                .hasSize(1).allSatisfy(event -> assertThat(event.target()).isEqualTo(target));
        String forbidden = completion.equals("commit") ? "rollback" : "commit";
        assertThat(events).noneMatch(event -> event.operation().equals(forbidden));
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
    }

    /** 本番のメソッド注釈ルーティングと実 TX を経由する外側呼び出し元。 */
    static class OuterCalls {
        private final AccessControlService accessControlService;

        OuterCalls(AccessControlService accessControlService) {
            this.accessControlService = accessControlService;
        }

        @Transactional
        public void write(boolean reject) {
            invoke(reject, DataSourceType.PRIMARY, false);
        }

        @Transactional(readOnly = true)
        public void read(boolean reject) {
            invoke(reject, DataSourceType.REPLICA, true);
        }

        private void invoke(boolean reject, DataSourceType expected, boolean readOnly) {
            assertOuterState(expected, readOnly);
            if (reject) {
                assertThatThrownBy(() -> accessControlService.checkMembership(ABSENT_ID, ABSENT_ID, "TEAM"))
                        .isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
            } else {
                assertThat(accessControlService.isMember(ABSENT_ID, ABSENT_ID, "TEAM")).isFalse();
            }
            // 内側から戻った直後を検査。outer TX 終了後の null だけでは復元欠落を見逃す。
            assertOuterState(expected, readOnly);
        }

        private void assertOuterState(DataSourceType expected, boolean readOnly) {
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(expected);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isEqualTo(readOnly);
        }
    }

    /** 共有基底の設定を維持し、軽量な BPP だけを static factory から登録する。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RoutingObservationConfiguration {
        @Bean
        static PoolObserver routingPoolObserver() {
            return new PoolObserver();
        }

        @Bean
        OuterCalls routingOuterCalls(AccessControlService service) {
            return new OuterCalls(service);
        }
    }

    /** 起動時の SQL や別スレッドを数えず、試験スレッドの実委譲結果だけを記録する。 */
    static class PoolObserver implements BeanPostProcessor {
        private final ThreadLocal<List<Observation>> observations = new ThreadLocal<>();

        void begin() {
            observations.set(new ArrayList<>());
        }

        void end() {
            observations.remove();
        }

        List<Observation> events() {
            return List.copyOf(observations.get());
        }

        void record(DataSourceType target, String operation) {
            List<Observation> events = observations.get();
            if (events != null) {
                events.add(new Observation(target, operation, DataSourceContextHolder.getDataSourceType(),
                        TransactionSynchronizationManager.isActualTransactionActive(),
                        TransactionSynchronizationManager.isCurrentTransactionReadOnly()));
            }
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof HikariDataSource pool) {
                if (beanName.equals("primaryDataSource")) {
                    return new ObservedDataSource(pool, DataSourceType.PRIMARY, this);
                }
                if (beanName.equals("replicaDataSource")) {
                    return new ObservedDataSource(pool, DataSourceType.REPLICA, this);
                }
            }
            return bean;
        }
    }

    /** Hikari と JDBC の処理を置換せず、成功した物理操作の選択先を観測する。 */
    static class ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource pool;
        private final DataSourceType target;
        private final PoolObserver observer;

        ObservedDataSource(HikariDataSource pool, DataSourceType target, PoolObserver observer) {
            super(pool);
            this.pool = pool;
            this.target = target;
            this.observer = observer;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(pool.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(pool.getConnection(username, password));
        }

        private Connection observe(Connection connection) {
            observer.record(target, "connection");
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = delegate(connection, method, args);
                        if (method.getName().equals("commit") || method.getName().equals("rollback")) {
                            observer.record(target, method.getName());
                        }
                        if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                            return observe(statement);
                        }
                        return result;
                    });
        }

        private PreparedStatement observe(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        Object result = delegate(statement, method, args);
                        if (method.getName().equals("executeQuery")) {
                            observer.record(target, "query");
                        }
                        return result;
                    });
        }

        private static Object delegate(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        @Override
        public void close() {
            pool.close();
        }
    }

    /** 実 pool と query 実行時の Spring TX / routing 状態。 */
    record Observation(DataSourceType target, String operation, DataSourceType holder,
                       boolean transactionActive, boolean readOnly) {
    }
}
