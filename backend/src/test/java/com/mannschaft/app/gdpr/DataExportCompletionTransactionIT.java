package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
    @MockitoBean private PersonalDataCollector collector;
    @MockitoBean private StorageService storage;
    @MockitoBean(name = "job-pool") private Executor jobs;
    @MockitoSpyBean private EncryptionService encryption;

    private static final long USER_ID = 9001316L;
    private static final UserEmailInfo USER = new UserEmailInfo(
            USER_ID, "zip-tx@example.com", "試験", "本人", PrefetchedPersonalData.none());

    @BeforeEach
    void 準備() {
        given(collector.collect(any(), any(), any())).willReturn(Map.of("profile.json", "[]"));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(jobs).execute(any(Runnable.class));
    }

    @Test
    void 異常_READYoutboxのREQUIRED失敗でもFAILEDを別TXで確定する() {
        DataExportEntity entity = service.requestExport(USER_ID, Set.of("profile"));
        AtomicBoolean first = new AtomicBoolean(true);
        // 実enqueueの@Transactional境界内で暗号化が失敗し、業務TXがrollback-onlyになる。
        doAnswer(invocation -> {
            if (first.getAndSet(false)) throw new IllegalStateException("owned outbox encryption failure");
            return invocation.callRealMethod();
        }).when(encryption).encryptBytes(any(byte[].class));

        service.processExportAsync(entity.getId(), USER, Set.of("profile"));

        verify(jobs).execute(any(Runnable.class));
        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(count("GDPR_EXPORT_READY", entity.getId())).isZero();
        assertThat(count("GDPR_EXPORT_FAILED", entity.getId())).isEqualTo(1);
    }

    @Test
    void 正常_完了と暗号化READYoutboxが同時に永続化される() {
        // 他テストのCOMPLETEDの24h上限とは異なる本人fixtureを使う。
        UserEmailInfo own = new UserEmailInfo(USER_ID + 1, "zip-tx-ok@example.com",
                "試験", "本人", PrefetchedPersonalData.none());
        DataExportEntity entity = service.requestExport(own.userId(), Set.of("profile"));
        service.processExportAsync(entity.getId(), own, Set.of("profile"));

        assertThat(exports.findById(entity.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        byte[] encrypted = jdbc.queryForObject(
                "SELECT payload_json FROM email_outbox WHERE idempotency_key = ?", byte[].class,
                "gdpr-export-ready:" + entity.getId());
        assertThat(encrypted).isNotNull();
        assertThat(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("ZIPパスワード");
        assertThat(new String(encryption.decryptBytes(encrypted), java.nio.charset.StandardCharsets.UTF_8))
                .contains("ZIPパスワード");
    }

    private long count(String kind, long exportId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE template_kind = ? AND idempotency_key = ?",
                Long.class, kind, (kind.equals("GDPR_EXPORT_READY") ? "gdpr-export-ready:" : "gdpr-export-failed:") + exportId);
    }
}
