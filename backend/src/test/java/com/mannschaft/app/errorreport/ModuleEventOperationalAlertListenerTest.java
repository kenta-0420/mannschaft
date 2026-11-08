package com.mannschaft.app.errorreport;

import com.mannschaft.app.errorreport.event.ModuleEventOperationalAlertListener;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.service.ErrorReportAsyncExecutor;
import com.mannschaft.app.errorreport.service.ErrorReportIngestService;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent.Kind;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * 実 Spring EventListener 配線で、非 TX の公開イベントから既存 Service 境界まで検証する。
 * 既存 Service の非同期処理・実 DB・通知配送はこの miniContext の合格範囲に含まない。
 * 後続 IT では実 ErrorReportAsyncExecutor / Ingest 経由の distinct errorHash 2件と、
 * 実 DB commit 後の既存運用通知パイプ到達を loopback 受信器で必須検証する。
 */
class ModuleEventOperationalAlertListenerTest {
    private final List<ForwardedReport> reports = new CopyOnWriteArrayList<>();
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void 実Spring購読を登録する() {
        ErrorReportService service = mock(ErrorReportService.class);
        doAnswer(call -> {
            reports.add(new ForwardedReport(call.getArgument(0), call.getArgument(1),
                    call.getArgument(2), Thread.currentThread(),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            return null;
        }).when(service).recordBackendException(any(Throwable.class), nullable(HttpServletRequest.class),
                any(ErrorReportSeverity.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(ErrorReportService.class, () -> service);
        context.register(ModuleEventOperationalAlertListener.class);
        context.refresh();
    }

    @AfterEach
    void contextを閉じる() {
        if (context != null) {
            context.close();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "QUARANTINED, MODULE_EVENT_QUARANTINED",
            "OLDEST_PENDING_30_MIN, MODULE_EVENT_OLDEST_PENDING_30_MIN"
    })
    void 公開イベントを固定コードとHIGHで既存Serviceへ一度渡す(Kind kind, String expectedCode) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        context.publishEvent(new ModuleEventOperationalAlertEvent(kind));
        assertEquals(1, reports.size(), "Spring の公開イベントが既存 Service へ届くこと");
        ForwardedReport report = reports.getFirst();
        assertEquals(expectedCode, report.failure().getMessage());
        assertEquals(IllegalStateException.class, report.failure().getClass());
        assertNull(report.failure().getCause(), "元例外・業務 payload を引き継がない");
        assertEquals(0, report.failure().getSuppressed().length);
        assertNull(report.request(), "HTTP / user / tenant の文脈を持ち込まない");
        assertEquals(ErrorReportSeverity.HIGH, report.severity());
        assertSame(Thread.currentThread(), report.thread(), "二重の非同期投入をしない");
        assertFalse(report.transactionActive());
    }

    @Test
    void 既存Executorの実分類からIngestへ渡る二種の集約keyを分離する() {
        context.publishEvent(new ModuleEventOperationalAlertEvent(Kind.QUARANTINED));
        context.publishEvent(new ModuleEventOperationalAlertEvent(Kind.OLDEST_PENDING_30_MIN));
        assertEquals(2, reports.size());
        List<ErrorReportEntity> classified = new CopyOnWriteArrayList<>();
        ErrorReportIngestService ingest = mock(ErrorReportIngestService.class);
        ErrorReportAsyncExecutor executor = new ErrorReportAsyncExecutor(ingest);
        for (ForwardedReport report : reports) {
            // 既存の実分類処理を呼び、hash の計算式は試験に複製しない。
            executor.doRecordBackendException(report.failure(), null, null, null, null, report.severity());
        }
        // ingest は package-private。可視性を変えず、実呼出しの Mockito 記録で境界を観測する。
        var ingestCalls = mockingDetails(ingest).getInvocations();
        assertEquals(2, ingestCalls.size());
        for (var call : ingestCalls) {
            assertEquals("ingest", call.getMethod().getName());
            Object source = call.getArgument(1);
            assertEquals("BACKEND", String.valueOf(source));
            classified.add(call.getArgument(0, ErrorReportEntity.class));
        }
        assertEquals(2, classified.size());
        assertNotNull(classified.get(0).getErrorHash());
        assertNotNull(classified.get(1).getErrorHash());
        assertNotEquals(classified.get(0).getErrorHash(), classified.get(1).getErrorHash());
        for (ErrorReportEntity report : classified) {
            assertEquals(ErrorReportSeverity.HIGH, report.getSeverity());
            assertEquals("backend", report.getPageUrl());
            assertNull(report.getUserId());
            assertNull(report.getOrganizationId());
            assertNull(report.getUserAgent());
            assertNull(report.getIpAddress());
        }
    }

    @Test
    void 背景の非TXスレッドから発行してもSpring購読へ届く() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Thread publisher = worker.submit(() -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                context.publishEvent(new ModuleEventOperationalAlertEvent(Kind.QUARANTINED));
                return Thread.currentThread();
            }).get(10, TimeUnit.SECONDS);
            assertEquals(1, reports.size());
            assertSame(publisher, reports.getFirst().thread());
            assertFalse(reports.getFirst().transactionActive());
        }
    }

    @Test
    void 公開イベントは許可された種別だけを持ちPII用の任意欄を持たない() {
        assertEquals(1, ModuleEventOperationalAlertEvent.class.getRecordComponents().length);
        assertEquals("kind", ModuleEventOperationalAlertEvent.class.getRecordComponents()[0].getName());
        assertEquals(Kind.class, ModuleEventOperationalAlertEvent.class.getRecordComponents()[0].getType());
        assertArrayEquals(new Kind[]{Kind.QUARANTINED, Kind.OLDEST_PENDING_30_MIN}, Kind.values());
    }

    @Test
    void null種別は公開前に拒否する() {
        assertThrows(NullPointerException.class, () -> new ModuleEventOperationalAlertEvent(null));
    }

    private record ForwardedReport(Throwable failure, HttpServletRequest request, ErrorReportSeverity severity,
            Thread thread, boolean transactionActive) {
    }
}
