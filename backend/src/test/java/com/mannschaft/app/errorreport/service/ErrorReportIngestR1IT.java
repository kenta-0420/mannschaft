package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261021-0021 リリース1 AC1〜5・7・8 の実MySQL先行試練。
 * hook未接続の同期timeoutやfixture失敗は実アサーションのredと区別する。
 * BACKENDバッファ計数はREOPEN・上限100・TTL・容量追出し・commit自体の失敗を除く。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ErrorReportIngestR1IT extends ErrorReportR1TestSupport {
    @Autowired private MeterRegistry meters;
    @Autowired private com.mannschaft.app.errorreport.ErrorReportProperties properties;

    @Test
    void AC1_空hashを二TXで同期し敗者だけ一回再試行して続く50件も集約する() throws Exception {
        var request = request();
        String hash = feHash(request);
        var barrier = new CyclicBarrier(2);
        AtomicInteger emptyReads = new AtomicInteger();
        hook.onRead = (key, found) -> {
            if (key.equals(hash) && !found && emptyReads.incrementAndGet() <= 2) {
                try { barrier.await(20, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException("同期点未成立（redの証拠にはしない）", ex); }
            }
        };
        double before = retryCount();

        var results = parallel(2, () -> fe(request));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(count(hash)).as("同じ空hashに非IGNOREDは一行だけ").isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(2);
        assertThat(emptyReads.get()).as("locking readの空結果二件を実際に同期した証拠").isEqualTo(2);
        assertThat(retryCount() - before).as("実MySQLのデッドロック敗者だけをretry").isEqualTo(1);
        hook.onRead = (key, found) -> { };

        var fifty = parallel(50, () -> fe(request));

        assertThat(fifty).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(count(hash)).isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(52);
    }

    @Test
    void AC1_IGNORED行だけが複数あるhashへの並行送信は新しい一行へ集約する() throws Exception {
        var request = request();
        String hash = feHash(request);
        var ignored1 = fixture(hash, ErrorReportStatus.IGNORED);
        var ignored2 = fixture(hash, ErrorReportStatus.IGNORED);

        var outcomes = parallel(12, () -> fe(request));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(count(hash)).isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(12);
        assertThat(reload(ignored1.getId()).getOccurrenceCount()).isEqualTo(1);
        assertThat(reload(ignored2.getId()).getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    void AC2_FE_RESOLVEDの並行再発は工程担当を解除しSLAをUTC保存する() throws Exception {
        var request = request();
        String hash = feHash(request);
        var report = fixture(hash, ErrorReportStatus.RESOLVED);

        var outcomes = parallel(8, () -> fe(request));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        var loaded = reload(report.getId());
        assertThat(loaded.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
        assertThat(loaded.getOccurrenceCount()).isEqualTo(9);
        assertThat(loaded.getWorkflowStage()).isNull();
        assertThat(loaded.getAssigneeId()).isNull();
        assertThat(loaded.getAffectedUserCount()).as("匿名観測-1でDB人数を減らさない").isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), sla_due_at) "
                + "FROM error_reports WHERE id = ?", Long.class, report.getId()))
                .isBetween(86400L - 60, 86400L + 60);
        assertThat(events.committed.stream().filter(ErrorReportRegressionDetectedEvent.class::isInstance)).hasSize(1);
    }

    @Test
    void AC2_BACKEND_RESOLVED再発は工程担当だけ解除しSLAを変えずバッファに積まない() {
        var ex = exception();
        String hash = backendHash(ex);
        var report = fixture(hash, ErrorReportStatus.RESOLVED);

        backend.doRecordBackendException(ex, null, null, null, null, ErrorReportSeverity.HIGH);

        var loaded = reload(report.getId());
        assertThat(loaded.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
        assertThat(loaded.getOccurrenceCount()).isEqualTo(2);
        assertThat(loaded.getWorkflowStage()).isNull();
        assertThat(loaded.getAssigneeId()).isNull();
        assertThat(loaded.getSlaDueAt()).isEqualTo(report.getSlaDueAt());
        assertThat(aggregator.drainAndClear()).isEmpty();
        verify(aiProvider, never()).analyze(any());
    }

    @Test
    void AC2_HEALTH_RESOLVEDと最新IGNOREDは同じidを返して一切変更しない() {
        String component = "cmp-r1-" + UUID.randomUUID();
        String hash = sha256("HealthDown:" + component);
        var resolved = fixture(hash, ErrorReportStatus.RESOLVED);

        assertThat(service.findOrCreateHealthDownReport(component)).isEqualTo(resolved.getId());
        assertThat(reload(resolved.getId()).getOccurrenceCount()).isEqualTo(1);
        assertThat(reload(resolved.getId()).getStatus()).isEqualTo(ErrorReportStatus.RESOLVED);
        tx.executeWithoutResult(s -> reports.deleteById(resolved.getId()));
        fixture(hash, ErrorReportStatus.IGNORED);
        var newest = fixture(hash, ErrorReportStatus.IGNORED);

        assertThatCode(() -> assertThat(service.findOrCreateHealthDownReport(component)).isEqualTo(newest.getId()))
                .doesNotThrowAnyException();
        assertThat(reload(newest.getId()).getOccurrenceCount()).isEqualTo(1);
        assertThat(events.committed).isEmpty();
    }

    @Test
    void AC3_FE50件でも匿名だけならHIGHに留まり昇格通知は一回だけ() {
        var request = request();
        for (int i = 0; i < 50; i++) fe(request);

        assertThat(occurrences(feHash(request))).isEqualTo(50);
        assertThat(reports.findByErrorHash(feHash(request)).orElseThrow().getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
        assertThat(events.committed.stream().filter(ErrorReportSeverityEscalatedEvent.class::isInstance)).hasSize(1);
        assertThat(events.committed.stream().filter(ErrorReportRaisedEvent.class::isInstance)).isEmpty();
    }

    @Test
    void AC3_BACKEND_HIGH新規だけRaisedし初回Slack判定を持ちAIは起動しない() {
        var high = exception();
        var medium = exception();
        backend.doRecordBackendException(high, null, null, null, null, ErrorReportSeverity.HIGH);
        backend.doRecordBackendException(medium, null, null, null, null, ErrorReportSeverity.MEDIUM);
        var highRow = reports.findByErrorHash(backendHash(high)).orElseThrow(); owned.add(highRow.getId());
        var mediumRow = reports.findByErrorHash(backendHash(medium)).orElseThrow(); owned.add(mediumRow.getId());

        assertThat(events.committed.stream().filter(ErrorReportRaisedEvent.class::isInstance)).hasSize(1);
        assertThat(events.committed.stream().filter(ErrorReportRaisedEvent.class::isInstance)
                .map(ErrorReportRaisedEvent.class::cast)).singleElement()
                .satisfies(event -> assertThat(event.slackEnabled()).isTrue());
        assertThat(highRow.getAffectedUserCount()).isZero();
        assertThat(highRow.getSlaDueAt()).isNull();
        assertThat(mediumRow.getSlaDueAt()).isNull();
        verify(aiProvider, never()).analyze(any());
    }

    @Test
    void AC4_実Async入口の並行発生が一行に集約されバッファ件数も成功件数と一致する() throws Exception {
        var ex = exception(); String hash = backendHash(ex);

        var submitted = parallel(30, () -> {
            backend.recordBackendException(ex, null, null, null, null, ErrorReportSeverity.MEDIUM); return null;
        });
        assertThat(submitted).allSatisfy(result -> assertThat(result.failure()).isNull());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(occurrences(hash)).isEqualTo(30));

        assertThat(count(hash)).isEqualTo(1);
        var report = reports.findByErrorHash(hash).orElseThrow(); owned.add(report.getId());
        assertThat(aggregator.drainAndClear().get(hash).occurrenceCount()).isEqualTo(30);
    }

    @Test
    void AC4_HEALTH入口の並行発生が一行と正しい件数になる() throws Exception {
        String component = "cmp-r1-" + UUID.randomUUID(); String hash = sha256("HealthDown:" + component);
        trackedHashes.add(hash);

        var outcomes = parallel(12, () -> service.findOrCreateHealthDownReport(component));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(count(hash)).isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(12);
        var report = reports.findByErrorHash(hash).orElseThrow(); owned.add(report.getId());
        assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.CRITICAL);
        assertThat(report.getAffectedUserCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), sla_due_at) "
                + "FROM error_reports WHERE id = ?", Long.class, report.getId())).isBetween(3600L - 60, 3600L + 60);
        assertThat(events.committed).isEmpty();
    }

    @Test
    void AC5_flush後のTX内失敗はDB通知AIバッファの全てをロールバックする() {
        properties.getAi().setEnabled(true);
        var request = com.mannschaft.app.errorreport.dto.ErrorReportRequest.builder()
                .errorMessage("R1 acceptance").pageUrl("/payment/" + UUID.randomUUID())
                .occurredAt(java.time.LocalDateTime.now()).build();
        String hash = feHash(request);
        hook.onFlush = key -> { if (hash.equals(key)) throw new IllegalStateException("R1 TX内の試験失敗"); };

        assertThatThrownBy(() -> fe(request)).isInstanceOf(IllegalStateException.class);

        assertThat(count(hash)).isZero();
        assertThat(events.committed).isEmpty();
        assertThat(aggregator.drainAndClear()).isEmpty();
        verify(aiProvider, never()).analyze(any());
    }

    @Test
    void AC5_ロック失敗の各試行は完全rollbackし五回で再送出する() {
        var request = request(); String hash = feHash(request);
        AtomicInteger attempts = new AtomicInteger();
        hook.onSaved = key -> {
            if (hash.equals(key)) { assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_reports WHERE error_hash = ?", Integer.class, hash)).isEqualTo(1); attempts.incrementAndGet(); throw new CannotAcquireLockException("R1 lock"); }
        };

        assertThatThrownBy(() -> fe(request)).isInstanceOf(CannotAcquireLockException.class);

        assertThat(count(hash)).as("各回のINSERTが巻き戻り、再試行に残らない").isZero();
        assertThat(attempts.get()).as("save直後のlock例外は別TXで五回まで再試行する").isEqualTo(5);
        assertThat(events.committed).isEmpty();
        assertThat(aggregator.drainAndClear()).isEmpty();
    }

    @Test
    void AC5_afterCommitでロック例外が起きてもcommit済みの集約を再試行しない() {
        var request = request(); String hash = feHash(request);
        AtomicInteger callbacks = new AtomicInteger();
        hook.onExistingRead = (key, found) -> {
            if (hash.equals(key)) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    callbacks.incrementAndGet(); throw new CannotAcquireLockException("R1 afterCommit失敗");
                }
            });
        };

        assertThatThrownBy(() -> fe(request)).isInstanceOf(CannotAcquireLockException.class);

        assertThat(count(hash)).isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(1);
        owned.add(reports.findByErrorHash(hash).orElseThrow().getId());
        assertThat(callbacks.get()).isEqualTo(1);
    }

    @Test
    void AC7_非IGNOREDがあるhashの再活性化は単票でもbulkでも409になる() {
        String hash = sha256(UUID.randomUUID().toString());
        fixture(hash, ErrorReportStatus.NEW);
        var ignored = fixture(hash, ErrorReportStatus.IGNORED);

        assertThatThrownBy(() -> service.updateStatus(ignored.getId(),
                new ErrorReportUpdateRequest("RESOLVED", null, null), null))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
        assertThatThrownBy(() -> service.bulkUpdate(new ErrorReportBulkUpdateRequest(List.of(ignored.getId()), "RESOLVED")))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
        assertThat(reload(ignored.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
    }

    @Test
    void AC7_Health復旧はIGNORED行も既解決行も変更しない() {
        var ignored = fixture(sha256(UUID.randomUUID().toString()), ErrorReportStatus.IGNORED);
        var resolved = fixture(sha256(UUID.randomUUID().toString()), ErrorReportStatus.RESOLVED);

        service.resolveHealthReport(ignored.getId()); service.resolveHealthReport(resolved.getId());

        assertThat(reload(ignored.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
        assertThat(reload(ignored.getId()).getResolvedAt()).isNull();
        assertThat(reload(resolved.getId()).getResolvedAt()).isNull();
    }

    @Test
    void AC8_集約と管理メモ更新の交差でも件数と管理メモを失わない() throws Exception {
        var request = request(); var saved = fe(request); String hash = saved.getErrorHash();
        var outcomes = parallel(20, () -> {
            fe(request);
            service.updateStatus(saved.getId(), new ErrorReportUpdateRequest(null, null, "R1更新"), null);
            return null;
        });

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(occurrences(hash)).isEqualTo(21);
        assertThat(reload(saved.getId()).getAdminNote()).isEqualTo("R1更新");
    }


    @Test
    void AC2_BACKEND_RESOLVEDの並行再発は一度だけ再開し残りを集約する() throws Exception {
        var ex = exception(); String hash = backendHash(ex);
        var report = fixture(hash, ErrorReportStatus.RESOLVED);
        var outcomes = parallel(12, () -> backend.doRecordBackendException(ex, null, null, null, null, ErrorReportSeverity.HIGH));

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        var loaded = reload(report.getId());
        assertThat(loaded.getOccurrenceCount()).isEqualTo(13);
        assertThat(loaded.getWorkflowStage()).isNull();
        assertThat(loaded.getAssigneeId()).isNull();
        assertThat(loaded.getSlaDueAt()).isEqualTo(report.getSlaDueAt());
        assertThat(events.committed.stream().filter(ErrorReportRegressionDetectedEvent.class::isInstance)).hasSize(1);
        assertThat(aggregator.drainAndClear().get(hash).occurrenceCount()).isEqualTo(11);
    }

    @Test
    void AC3_FE_HIGH新規と再開だけがcommit後に外部AIを一回ずつ呼ぶ() {
        boolean enabled = properties.getAi().isEnabled();
        properties.getAi().setEnabled(true);
        try {
            var request = request();
            var highRequest = com.mannschaft.app.errorreport.dto.ErrorReportRequest.builder()
                    .errorMessage(request.getErrorMessage()).pageUrl("/payment/" + UUID.randomUUID())
                    .occurredAt(request.getOccurredAt()).build();
            var report = fe(highRequest);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_report_ai_analyses WHERE error_report_id = ?",
                            Integer.class, report.getId())).isEqualTo(1));
            assertThat(events.committed.stream().filter(ErrorReportRaisedEvent.class::isInstance)).hasSize(1);
            assertThat(events.committed.stream().filter(
                    com.mannschaft.app.errorreport.event.ErrorReportAiAnalysisRequestedEvent.class::isInstance)).hasSize(1);
            org.mockito.Mockito.verify(aiProvider, org.mockito.Mockito.times(1)).analyze(any());
            tx.executeWithoutResult(state -> {
                var managed = reports.findById(report.getId()).orElseThrow(); managed.setStatus(ErrorReportStatus.RESOLVED);
            });
            fe(highRequest);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_report_ai_analyses WHERE error_report_id = ?",
                            Integer.class, report.getId())).isEqualTo(2));
            org.mockito.Mockito.verify(aiProvider, org.mockito.Mockito.times(2)).analyze(any());
            assertThat(events.committed.stream().filter(
                    com.mannschaft.app.errorreport.event.ErrorReportAiAnalysisRequestedEvent.class::isInstance)).hasSize(2);
        } finally { properties.getAi().setEnabled(enabled); }
    }


    @Test
    void AC5_BACKENDのflush後失敗は通知とバッファを残さない() {
        var ex = exception(); String hash = backendHash(ex);
        hook.onFlush = key -> { if (hash.equals(key)) throw new IllegalStateException("R1 BACKEND rollback"); };

        assertThatThrownBy(() -> backend.doRecordBackendException(ex, null, null, null, null, ErrorReportSeverity.HIGH))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(hash)).isZero();
        assertThat(events.committed).isEmpty();
        assertThat(aggregator.drainAndClear()).isEmpty();
        verify(aiProvider, never()).analyze(any());
    }

    @Test
    void AC5_二回のsave後失敗が完全rollbackしてから新TXで成功する() {
        var request = request(); String hash = feHash(request);
        var insertedIds = new java.util.ArrayList<Long>();
        var foundRows = new java.util.ArrayList<Boolean>();
        hook.onExistingRead = (key, found) -> { if (hash.equals(key)) foundRows.add(found); };
        hook.onSaved = key -> {
            if (hash.equals(key)) {
                insertedIds.add(jdbc.queryForObject("SELECT id FROM error_reports WHERE error_hash = ?", Long.class, hash));
                if (insertedIds.size() <= 2) throw new CannotAcquireLockException("R1 rollbackして再試行");
            }
        };

        var saved = new java.util.concurrent.atomic.AtomicReference<ErrorReportEntity>();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> saved.set(fe(request)));
        assertThat(count(hash)).as("失敗ならINSERTはTX外に残らず、成功なら一行だけ").isEqualTo(failure == null ? 1 : 0);
        assertThat(failure).as("二回rollback後の三回目で成功する").isNull();

        assertThat(foundRows).containsExactly(false, false, false);
        assertThat(insertedIds).hasSize(3).doesNotHaveDuplicates();
        assertThat(saved.get().getId()).isEqualTo(insertedIds.getLast());
        assertThat(count(hash)).isEqualTo(1);
        assertThat(occurrences(hash)).isEqualTo(1);
        assertThat(events.committed).isEmpty();
    }

    @Test
    void AC5_read直後のTX内失敗も新規行を残さない() {
        var request = request(); String hash = feHash(request);
        hook.onExistingRead = (key, found) -> { if (hash.equals(key)) throw new IllegalStateException("R1 flush前"); };

        assertThatThrownBy(() -> fe(request)).isInstanceOf(IllegalStateException.class);

        assertThat(count(hash)).isZero();
        assertThat(events.committed).isEmpty();
        assertThat(aggregator.drainAndClear()).isEmpty();
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ErrorReportSeverity.class)
    void AC2_FEの全severityのSLAはDBのUTC現在時刻から規定値になる(ErrorReportSeverity severity) {
        var request = request(); String hash = feHash(request);
        var report = fixture(hash, ErrorReportStatus.RESOLVED);
        tx.executeWithoutResult(state -> reports.findById(report.getId()).orElseThrow().setSeverity(severity));

        fe(request);

        var expected = com.mannschaft.app.errorreport.SlaPolicy.durationFor(severity);
        Long seconds = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), sla_due_at) "
                + "FROM error_reports WHERE id = ?", Long.class, report.getId());
        if (expected.isEmpty()) assertThat(seconds).isNull();
        else assertThat(seconds).isBetween(expected.orElseThrow().getSeconds() - 60, expected.orElseThrow().getSeconds() + 60);
    }

    @Test
    void AC3_BACKEND新規でも既存バッファがあればRaisedのSlackだけ抑制する() {
        var ex = exception(); String hash = backendHash(ex);
        aggregator.addOccurrence(hash, "既存バッファ", ErrorReportSeverity.HIGH);

        var report = backend.doRecordBackendException(ex, null, null, null, null, ErrorReportSeverity.HIGH);
        owned.add(report.getId());

        assertThat(events.committed.stream().filter(ErrorReportRaisedEvent.class::isInstance)
                .map(ErrorReportRaisedEvent.class::cast)).singleElement()
                .satisfies(event -> assertThat(event.slackEnabled()).isFalse());
        assertThat(aggregator.drainAndClear().get(hash).occurrenceCount()).isEqualTo(2);
        verify(aiProvider, never()).analyze(any());
    }

    @Test
    void AC5_ロック競合以外の整合性例外は再試行しない() {
        var request = request(); String hash = feHash(request); AtomicInteger attempts = new AtomicInteger();
        hook.onSaved = key -> {
            if (hash.equals(key)) { assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM error_reports WHERE error_hash = ?", Integer.class, hash)).isEqualTo(1); attempts.incrementAndGet(); throw new org.springframework.dao.DataIntegrityViolationException("R1 unrelated CHECK"); }
        };

        assertThatThrownBy(() -> fe(request)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(attempts.get()).isEqualTo(1);
        assertThat(count(hash)).isZero();
    }


    @Test
    void AC1_指揮役は外側TXを停止しRedisをTX外で観測して内側commitを保持する() {
        var base = request();
        var request = com.mannschaft.app.errorreport.dto.ErrorReportRequest.builder()
                .errorMessage(base.getErrorMessage()).pageUrl(base.getPageUrl()).occurredAt(base.getOccurredAt()).userId(991232L).build();
        String hash = feHash(request);

        assertThatThrownBy(() -> tx.executeWithoutResult(outer -> {
            fe(request); throw new IllegalStateException("R1 外側TXだけrollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count(hash)).as("NOT_SUPPORTED指揮役の内側TXは外側rollbackに参加しない").isEqualTo(1);
        assertThat(redisTxStates).isNotEmpty().allMatch(active -> !active);
    }

    @Test
    void AC7_管理指揮役も外側TXを停止して独立commitする() {
        var report = fixture(sha256(UUID.randomUUID().toString()), ErrorReportStatus.IGNORED);

        assertThatThrownBy(() -> tx.executeWithoutResult(outer -> {
            service.updateStatus(report.getId(), new ErrorReportUpdateRequest("RESOLVED", null, null), null);
            throw new IllegalStateException("R1 管理外側TXだけrollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reload(report.getId()).getStatus()).isEqualTo(ErrorReportStatus.RESOLVED);
    }


    @Test
    void AC3_FEは九件でMEDIUM十件でHIGHとなり五十件でも匿名ならHIGHを保つ() {
        var request = request();
        for (int i = 1; i <= 50; i++) {
            var report = fe(request);
            if (i == 9) assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.MEDIUM);
            if (i == 10 || i == 49 || i == 50) assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
            assertThat(report.getOccurrenceCount()).isEqualTo(i);
        }
    }

    @Test
    void AC3_BACKENDは九件でMEDIUM十件でHIGH四十九件でHIGH五十件でCRITICALになる() {
        var ex = exception(); backendHash(ex);
        for (int i = 1; i <= 50; i++) {
            var report = backend.doRecordBackendException(ex, null, null, null, null, ErrorReportSeverity.MEDIUM);
            owned.add(report.getId());
            if (i == 9) assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.MEDIUM);
            if (i == 10 || i == 49) assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
            if (i == 50) assertThat(report.getSeverity()).isEqualTo(ErrorReportSeverity.CRITICAL);
            assertThat(report.getOccurrenceCount()).isEqualTo(i);
        }
    }

    private double retryCount() {
        return meters.find("mannschaft.errorreport.ingest.retries").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static RuntimeException exception() {
        var ex = new IllegalStateException("R1 backend acceptance");
        ex.setStackTrace(new StackTraceElement[] { new StackTraceElement("R1", UUID.randomUUID().toString(), "R1.java", 1) });
        return ex;
    }

    private String backendHash(Throwable ex) {
        String hash = sha256(ex.getClass().getName() + "|" + ex.getStackTrace()[0]);
        trackedHashes.add(hash);
        return hash;
    }
}
