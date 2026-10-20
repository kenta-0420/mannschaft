package com.mannschaft.app.gdpr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.gdpr.dto.UserEmailInfo;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261017-0549 G6。実 Bean・実 commit・実 MySQL で非同期収集失敗後の永続状態を観測する。
 * テスト全体を TX で包まず fixture を先に commit し、外部 Storage だけをモックする。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("GDPR 本人chat監査の収集失敗は非同期FAILEDになる")
class ChatAuditExportFailureIT extends AbstractMySqlIntegrationTest {

    private static final long OWNER = 930001L;
    @Autowired private DataExportService exportService;
    @Autowired private DataExportRepository exports;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    // 実collectorの結果は置換せず、対象引数で発生した例外を非同期スレッドから観測する。
    @MockitoSpyBean private PersonalDataCollector collector;
    // 既存の R2 具体型依存も保つため、外部ストレージ実装の型で置換する。
    @MockitoBean private R2StorageService storage;

    private Long exportId;

    @ParameterizedTest
    @ValueSource(strings = {"chat_messages", "audit_logs"})
    void G6_事前取得が欠けたら空の成功ZIPを出さずFAILEDをcommitする(String category) {
        assertFailed(category, PrefetchedPersonalData.none(),
                category + " は PersonalDataPrefetcher による事前取得が必要", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat_messages", "audit_logs"})
    void G6_直列化不能な事前取得値を空の成功ZIPへ変えずFAILEDにする(String category) {
        // Jackson の空 Bean 直列化失敗を起こし、DB 読み取り成功後の書き出し失敗を独立に観測する。
        List<Map<String, Object>> invalid = List.of(Map.of("unserializable", new Object()));
        PrefetchedPersonalData prefetched = "chat_messages".equals(category)
                ? new PrefetchedPersonalData(null, invalid, null)
                : new PrefetchedPersonalData(null, null, invalid);
        assertFailed(category, prefetched, category + " の書き出しに失敗した", JsonProcessingException.class);
    }

    private void assertFailed(String category, PrefetchedPersonalData prefetched,
                              String internalMessage, Class<? extends Throwable> internalCause) {
        AtomicReference<RuntimeException> collectionFailure = new AtomicReference<>();
        AtomicReference<Thread> collectionThread = new AtomicReference<>();
        AtomicReference<Boolean> collectionTransaction = new AtomicReference<>();
        Thread callerThread = Thread.currentThread();
        doAnswer(invocation -> {
            collectionThread.set(Thread.currentThread());
            collectionTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException failure) {
                collectionFailure.set(failure);
                throw failure;
            }
        }).when(collector).collect(eq(OWNER), eq(Set.of(category)), same(prefetched));
        exportId = new TransactionTemplate(transactionManager).execute(status -> exports.saveAndFlush(
                DataExportEntity.builder().userId(OWNER).status("PENDING").categories(category)
                        .progressPercent(0).build()).getId());
        UserEmailInfo user = new UserEmailInfo(OWNER, "gdpr-fixture@example.invalid", "試験", "本人", prefetched);

        exportService.processExportAsync(exportId, user, Set.of(category));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            DataExportEntity result = exports.findById(exportId).orElseThrow();
            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getS3Key()).isNull();
            // 公開DB文言へ例外本文を戻さず、内部の対象経路を別に厳密検証する。
            assertThat(result.getErrorMessage()).isEqualTo("エクスポート処理に失敗しました");
            assertThat(collectionThread.get()).isNotNull().isNotSameAs(callerThread);
            assertThat(collectionTransaction.get()).isTrue();
            assertThat(collectionFailure.get()).isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage(internalMessage);
            if (internalCause == null) assertThat(collectionFailure.get()).hasNoCause();
            else assertThat(collectionFailure.get()).hasCauseInstanceOf(internalCause);
        });
        verify(collector).collect(eq(OWNER), eq(Set.of(category)), same(prefetched));
        verify(storage, never()).upload(anyString(), any(byte[].class), eq("application/zip"));
    }

    @AfterEach
    void 所有するfixtureだけを削除する() {
        if (exportId == null) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM email_outbox WHERE source_domain = ? AND source_event_id IN (?, ?)",
                    "gdpr", "gdpr-export-ready:" + exportId, "gdpr-export-failed:" + exportId);
            exports.deleteById(exportId);
        });
    }
}
