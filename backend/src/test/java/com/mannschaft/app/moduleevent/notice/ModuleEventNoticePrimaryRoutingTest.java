package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.config.ReplicaRoutingAspect;
import com.mannschaft.app.config.RoutingDataSource;
import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 実 ReplicaRoutingAspect・RoutingDataSource・TX proxy で物理接続取得先を観測する小 Spring 試練。
 * EntityManager の空結果だけを mock とし、SQL の業務意味は既存実 DB Read28 が担保する。
 * App/DB は起動せず、固定された relay red 入力も変更しない。
 */
class ModuleEventNoticePrimaryRoutingTest {
    @AfterEach
    void routing指定を残さない() {
        DataSourceContextHolder.clear();
    }

    @Test
    void 外側REPLICA指定でもsnapshotはPRIMARY接続を取り指定を復元する() throws SQLException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            Trace trace = context.getBean(Trace.class);
            DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
            assertThat(context.getBean(ModuleEventNoticeReadService.class).snapshot(1)).isEmpty();
            assertThat(trace.targets).containsExactly(DataSourceType.PRIMARY);
            verify(trace.connections.getFirst()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    @Test
    void readOnlycallerから公開APIが独立PRIMARYを取得しcaller接続と属性を復元する() throws SQLException {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            context.getBean(ReadOnlyCaller.class).readThroughApi();
            Trace trace = context.getBean(Trace.class);
            assertThat(trace.targets).containsExactly(DataSourceType.REPLICA, DataSourceType.PRIMARY);
            verify(trace.connections.get(1)).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Fixtures {
        @Bean Trace trace() { return new Trace(); }
        @Bean ReplicaRoutingAspect routingAspect() { return new ReplicaRoutingAspect(); }
        @Bean RoutingDataSource dataSource(Trace trace) {
            RoutingDataSource routing = new RoutingDataSource();
            RecordingDataSource primary = new RecordingDataSource(DataSourceType.PRIMARY, trace);
            RecordingDataSource replica = new RecordingDataSource(DataSourceType.REPLICA, trace);
            routing.setTargetDataSources(Map.of(DataSourceType.PRIMARY, primary, DataSourceType.REPLICA, replica));
            routing.setDefaultTargetDataSource(primary);
            return routing;
        }
        @Bean DataSourceTransactionManager transactionManager(RoutingDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean ModuleEventImpactResolverRegistry registry() { return new ModuleEventImpactResolverRegistry(List.of()); }
        @Bean ModuleEventImpactEvaluator evaluator(ModuleEventImpactResolverRegistry registry) {
            return new ModuleEventImpactEvaluator(registry);
        }
        @Bean ModuleEventNoticeReadService reads(ModuleEventImpactResolverRegistry registry) {
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
            when(query.getResultList()).thenReturn(List.of());
            return new ModuleEventNoticeReadService(entityManager, registry);
        }
        @Bean ModuleEventNoticeRelayService relay(ModuleEventImpactResolverRegistry registry) {
            return new ModuleEventNoticeRelayService(mock(EntityManager.class), registry);
        }
        @Bean ModuleEventNoticeOutboxApi api(ModuleEventNoticeReadService reads, ModuleEventImpactEvaluator evaluator,
                DataSourceTransactionManager transactions, ModuleEventNoticeRelayService relay) {
            return new ModuleEventNoticeOutboxApi(reads, evaluator, transactions, relay);
        }
        @Bean ReadOnlyCaller caller(ModuleEventNoticeOutboxApi api, RoutingDataSource dataSource) {
            return new ReadOnlyCaller(api, dataSource);
        }
    }

    public static class ReadOnlyCaller {
        private final ModuleEventNoticeOutboxApi api;
        private final RoutingDataSource dataSource;
        public ReadOnlyCaller(ModuleEventNoticeOutboxApi api, RoutingDataSource dataSource) {
            this.api = api;
            this.dataSource = dataSource;
        }
        @Transactional(readOnly = true)
        public void readThroughApi() {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            Connection connection = DataSourceUtils.getConnection(dataSource);
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
            assertThat(api.readDescriptor(1)).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(dataSource)).isSameAs(resource);
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection);
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
        }
    }

    static class Trace {
        final List<DataSourceType> targets = new ArrayList<>();
        final List<Connection> connections = new ArrayList<>();
    }

    static class RecordingDataSource extends AbstractDataSource {
        private final DataSourceType target;
        private final Trace trace;
        RecordingDataSource(DataSourceType target, Trace trace) { this.target = target; this.trace = trace; }
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
            trace.targets.add(target);
            trace.connections.add(connection);
            return connection;
        }
        @Override
        public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    }
}
