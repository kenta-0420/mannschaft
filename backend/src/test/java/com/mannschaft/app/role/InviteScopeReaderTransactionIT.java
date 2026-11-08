package com.mannschaft.app.role;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.organization.service.OrganizationInviteScopeStateAdapter;
import com.mannschaft.app.team.service.TeamInviteScopeStateAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * G21: 具象readerの実TX/primary/SQLを観測する。
 *
 * <p>許可済の試験例外として実Beanをcall-through spyし、実読取の後にだけ診断SELECTを実行する。
 * 返り値/処理/transactionを置換せず、最初のparent SELECTのsnapshot時点も変えない。
 * SQL捕捉は既存ActivityAutomaticScheduleIntegrationTestのListAppender手法を再利用する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class InviteScopeReaderTransactionIT extends InviteGenerationItSupport {
    @MockitoSpyBean private TeamInviteScopeStateAdapter teamReader;
    @MockitoSpyBean private OrganizationInviteScopeStateAdapter organizationReader;

    private record Observation(Long connection, boolean transactionActive, boolean readOnly, DataSourceType route, List<String> sql) { }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G21: 親readerは別物理connectionのprimary書込可TXで親SELECTだけを実行する")
    void readerConnectionAndSqlAreObservedInsideItsActualTransaction(String type) throws Exception {
        Scope scope = scope(type);
        Class<?> adapterClass = "TEAM".equals(type) ? TeamInviteScopeStateAdapter.class : OrganizationInviteScopeStateAdapter.class;
        for (String method : List.of("readCommittedStateForInviteDecision", "recheckCommittedStateAfterInviteIssue")) {
            Transactional tx = adapterClass.getMethod(method, Long.class).getAnnotation(Transactional.class);
            assertThat(tx).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            assertThat(tx.readOnly()).isFalse();
        }
        AtomicReference<Observation> observed = new AtomicReference<>();
        if ("TEAM".equals(type)) {
            doAnswer(invocation -> observeRealReader(invocation, observed)).when(teamReader).readCommittedStateForInviteDecision(scope.id());
        } else {
            doAnswer(invocation -> observeRealReader(invocation, observed)).when(organizationReader).readCommittedStateForInviteDecision(scope.id());
        }

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long outerConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 99 WHERE id = ?", scope.id());
            var state = "TEAM".equals(type) ? teamReader.readCommittedStateForInviteDecision(scope.id())
                    : organizationReader.readCommittedStateForInviteDecision(scope.id());
            assertThat(state).isPresent();
            assertThat(state.orElseThrow().closureGeneration()).isZero();
            Observation inner = observed.get();
            assertThat(inner).isNotNull();
            assertThat(inner.connection()).isNotEqualTo(outerConnection);
            assertThat(inner.transactionActive()).isTrue();
            assertThat(inner.readOnly()).isFalse();
            assertThat(inner.route()).isEqualTo(DataSourceType.PRIMARY);
            assertThat(inner.sql()).hasSize(1).allSatisfy(sql -> {
                String normalized = sql.strip().toLowerCase(Locale.ROOT);
                assertThat(normalized).startsWith("select").contains(scope.table())
                        .doesNotContain("for update", "for share", "lock in share mode");
            });
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(outerConnection);
            status.setRollbackOnly();
        });
    }

    private Object observeRealReader(InvocationOnMock invocation, AtomicReference<Observation> observed) throws Throwable {
        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previous = sqlLogger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        sqlLogger.addAppender(capture);
        sqlLogger.setLevel(Level.DEBUG);
        Object result;
        try {
            result = invocation.callRealMethod();
        } finally {
            sqlLogger.detachAppender(capture);
            sqlLogger.setLevel(previous);
            capture.stop();
        }
        String ownThread = Thread.currentThread().getName();
        List<String> statements = capture.list.stream().filter(event -> ownThread.equals(event.getThreadName()))
                .map(ILoggingEvent::getFormattedMessage).toList();
        observed.set(new Observation(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                DataSourceContextHolder.getDataSourceType(), statements));
        return result;
    }
}
