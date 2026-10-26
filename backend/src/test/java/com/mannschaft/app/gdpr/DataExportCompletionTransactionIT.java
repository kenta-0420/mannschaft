package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * 完了状態と実物の暗号化outboxをcommit後の別接続で検証する。
 * job-poolだけを同期化し、非同期処理の終了前にDBを読む競合をなくす。
 * 外側テストTXは張らず、MySQLは既存Testcontainers基盤を使う。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DataExportCompletionTransactionIT extends AbstractMySqlIntegrationTest {
    @Autowired private DataExportService service;
    @Autowired private DataExportRepository exports;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    // Storageは外部I/O境界。具象型を必要とする既存管理Beanとの型互換も維持する。
    @MockitoBean private R2StorageService storage;
    @MockitoBean(name = "job-pool") private Executor jobs;
    @MockitoSpyBean private EncryptionService encryption;

    private static final long USER_ID = 9001316L;
    private static final PrefetchedPersonalData PREFETCHED = new PrefetchedPersonalData(
            List.of(Map.of("id", 1315L, "teamId", 1L, "organizationId", 2L, "invitedBy", USER_ID)));
    private static final UserEmailInfo USER = new UserEmailInfo(
            USER_ID, "zip-tx@example.com", "試験", "本人", PREFETCHED);

    @BeforeEach
    void 準備() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(jobs).execute(any(Runnable.class));
    }

    @Test
    void 異常_READYoutboxのREQUIRED失敗でもFAILEDを別TXで確定する() {
        DataExportEntity entity = service.requestExport(USER_ID, Set.of("memberships"));
        AtomicBoolean first = new AtomicBoolean(true);
        // 実enqueueの@Transactional境界内で暗号化が失敗し、業務TXがrollback-onlyになる。
        doAnswer(invocation -> {
            if (first.getAndSet(false)) throw new IllegalStateException("owned outbox encryption failure");
            return invocation.callRealMethod();
        }).when(encryption).encryptBytes(any(byte[].class));

        service.processExportAsync(entity.getId(), USER, Set.of("memberships"));

        verify(jobs).execute(any(Runnable.class));
        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("GDPR_EXPORT_READY", entity.getId())).isZero();
        assertThat(count("GDPR_EXPORT_FAILED", entity.getId())).isEqualTo(1);
        verify(storage).delete("gdpr-exports/" + USER_ID + "/" + entity.getId() + ".zip");
    }

    @Test
    void 異常_呼出し元TXを外部削除へ持ち込まずrollback後もFAILEDと失敗通知を保持する() {
        UserEmailInfo own = new UserEmailInfo(USER_ID + 3, "zip-tx-caller@example.com",
                "試験", "本人", new PrefetchedPersonalData(List.of()));
        DataExportEntity entity = service.requestExport(own.userId(), Set.of("memberships"));
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);
        AtomicBoolean cleanupHadTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) throw new IllegalStateException("owned caller outbox failure");
            return invocation.callRealMethod();
        }).when(encryption).encryptBytes(any(byte[].class));
        doAnswer(invocation -> {
            cleanupCalled.set(true);
            cleanupHadTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(storage).delete("gdpr-exports/" + own.userId() + "/" + entity.getId() + ".zip");

        // 同期job executorでもSpring proxyを通し、実呼出し元TXの中断・再開を観測する。
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        caller.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            service.processExportAsync(entity.getId(), own, Set.of("memberships"));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();
        });

        assertThat(cleanupCalled).isTrue();
        assertThat(cleanupHadTransaction).isFalse();
        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("GDPR_EXPORT_READY", entity.getId())).isZero();
        assertThat(count("GDPR_EXPORT_FAILED", entity.getId())).isEqualTo(1);
        verify(jobs).execute(any(Runnable.class));
    }

    @Test
    void 異常_失敗通知も登録できなくてもFAILEDを巻き戻さない() {
        UserEmailInfo own = new UserEmailInfo(USER_ID + 2, "zip-tx-fail@example.com",
                "試験", "本人", new PrefetchedPersonalData(List.of()));
        DataExportEntity entity = service.requestExport(own.userId(), Set.of("memberships"));
        org.mockito.Mockito.doThrow(new IllegalStateException("owned persistent outbox failure"))
                .when(encryption).encryptBytes(any(byte[].class));

        service.processExportAsync(entity.getId(), own, Set.of("memberships"));

        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("GDPR_EXPORT_READY", entity.getId())).isZero();
        assertThat(count("GDPR_EXPORT_FAILED", entity.getId())).isZero();
    }

    @Test
    void 正常_完了と暗号化READYoutboxが同時に永続化される() {
        // 他テストのCOMPLETEDの24h上限とは異なる本人fixtureを使う。
        UserEmailInfo own = new UserEmailInfo(USER_ID + 1, "zip-tx-ok@example.com",
                "試験", "本人", new PrefetchedPersonalData(List.of()));
        DataExportEntity entity = service.requestExport(own.userId(), Set.of("memberships"));
        service.processExportAsync(entity.getId(), own, Set.of("memberships"));

        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        byte[] encrypted = jdbc.queryForObject(
                "SELECT payload_json FROM email_outbox WHERE source_event_id = ?", byte[].class,
                "gdpr-export-ready:" + entity.getId());
        assertThat(encrypted).isNotNull();
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ZIPパスワード");
        String payload = new String(encryption.decryptBytes(encrypted), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("ZIPパスワード");
        try {
            String body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).get("body").asText();
            String password = body.substring(body.indexOf("<strong>") + 8, body.indexOf("</strong>"));
            assertThat(org.springframework.security.crypto.bcrypt.BCrypt.checkpw(password,
                    exports.findById(entity.getId()).orElseThrow().getZipPasswordHash())).isTrue();
            org.mockito.ArgumentCaptor<byte[]> archive = org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(storage).upload(any(), archive.capture(), any());
            try (var zip = new net.lingala.zip4j.io.inputstream.ZipInputStream(
                    new java.io.ByteArrayInputStream(archive.getValue()), password.toCharArray())) {
                var entry = zip.getNextEntry();
                assertThat(entry.getFileName()).isEqualTo("memberships.json");
                assertThat(entry.isEncrypted()).isTrue();
                assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("[]");
                assertThat(zip.getNextEntry()).isNull();
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("暗号化outboxのpasswordと実ZIPを照合できない", e);
        }
    }

    private long count(String kind, long exportId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE template_kind = ? AND source_event_id = ?",
                Long.class, kind, (kind.equals("GDPR_EXPORT_READY") ? "gdpr-export-ready:" : "gdpr-export-failed:") + exportId);
    }
}
