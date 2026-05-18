package com.mannschaft.app.pointcard.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.pointcard.service.ProviderMatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PointCardRematchBatchService} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §15 Phase 5 P5-S4
 *
 * <p>テストケース（8 件）:
 * <ol>
 *   <li>正常系・全件マッチ</li>
 *   <li>正常系・部分マッチ</li>
 *   <li>正常系・全件未マッチ</li>
 *   <li>個別失敗時スキップ続行</li>
 *   <li>空テーブル</li>
 *   <li>チャンク境界（chunk-size=2 で 5 件 → 3 ページ）</li>
 *   <li>高失敗率（20%）→ Sentry HIGH 通知 1 回</li>
 *   <li>低失敗率（5%）→ Sentry 通知なし</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class PointCardRematchBatchServiceTest {

    @Mock
    private UserPointCardRepository userPointCardRepository;

    @Mock
    private ProviderMatchService providerMatchService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ErrorReportService errorReportService;

    @InjectMocks
    private PointCardRematchBatchService service;

    @BeforeEach
    void setUp() {
        // @Value 注入は手動で差し込む（テスト個別に上書き可能）
        ReflectionTestUtils.setField(service, "chunkSize", 1000);
    }

    // ──────────────────────────────────────────────
    // 1. 正常系: 全件マッチ
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("3 件すべてマッチ → save 3 回 + 集計監査ログ {total:3, matched:3, skipped:0}")
    void allMatched() {
        UserPointCardEntity card1 = card("01928a3e-0001-7000-8000-000000000001", "ドコモポイント");
        UserPointCardEntity card2 = card("01928a3e-0002-7000-8000-000000000002", "Tポイント");
        UserPointCardEntity card3 = card("01928a3e-0003-7000-8000-000000000003", "Pontaポイント");

        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card1, card2, card3), PageRequest.of(0, 1000), 3));

        PointCardProviderEntity p1 = provider();
        PointCardProviderEntity p2 = provider();
        PointCardProviderEntity p3 = provider();
        when(providerMatchService.matchProvider("ドコモポイント")).thenReturn(Optional.of(p1));
        when(providerMatchService.matchProvider("Tポイント")).thenReturn(Optional.of(p2));
        when(providerMatchService.matchProvider("Pontaポイント")).thenReturn(Optional.of(p3));

        service.execute();

        verify(userPointCardRepository, times(3)).save(any(UserPointCardEntity.class));
        assertMetadataContains("\"total\":3", "\"matched\":3", "\"skipped\":0");
        verify(errorReportService, never())
                .recordBackendException(any(Throwable.class), any(HttpServletRequest.class), any(ErrorReportSeverity.class));
    }

    // ──────────────────────────────────────────────
    // 2. 正常系: 部分マッチ（3 件中 2 件マッチ）
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("3 件中 2 件マッチ・1 件未マッチ → save 2 回、集計 {total:3, matched:2, skipped:0}")
    void partialMatched() {
        UserPointCardEntity card1 = card("01928a3e-0001-7000-8000-000000000001", "ドコモポイント");
        UserPointCardEntity card2 = card("01928a3e-0002-7000-8000-000000000002", "知らないカード");
        UserPointCardEntity card3 = card("01928a3e-0003-7000-8000-000000000003", "Pontaポイント");

        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card1, card2, card3), PageRequest.of(0, 1000), 3));

        when(providerMatchService.matchProvider("ドコモポイント")).thenReturn(Optional.of(provider()));
        when(providerMatchService.matchProvider("知らないカード")).thenReturn(Optional.empty());
        when(providerMatchService.matchProvider("Pontaポイント")).thenReturn(Optional.of(provider()));

        service.execute();

        verify(userPointCardRepository, times(2)).save(any(UserPointCardEntity.class));
        assertMetadataContains("\"total\":3", "\"matched\":2", "\"skipped\":0");
    }

    // ──────────────────────────────────────────────
    // 3. 正常系: 全件未マッチ
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("5 件すべて Optional.empty → save ゼロ、集計 {total:5, matched:0, skipped:0}")
    void allUnmatched() {
        List<UserPointCardEntity> cards = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            cards.add(card("01928a3e-000" + i + "-7000-8000-00000000000" + i, "未知カード" + i));
        }

        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards, PageRequest.of(0, 1000), 5));
        when(providerMatchService.matchProvider(any())).thenReturn(Optional.empty());

        service.execute();

        verify(userPointCardRepository, never()).save(any(UserPointCardEntity.class));
        assertMetadataContains("\"total\":5", "\"matched\":0", "\"skipped\":0");
    }

    // ──────────────────────────────────────────────
    // 4. 個別失敗時スキップ続行
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("3 件中 1 件で matchProvider が例外 → skipped:1、他 2 件は処理続行")
    void skipOnIndividualFailure() {
        UserPointCardEntity card1 = card("01928a3e-0001-7000-8000-000000000001", "ドコモポイント");
        UserPointCardEntity card2 = card("01928a3e-0002-7000-8000-000000000002", "BAD");
        UserPointCardEntity card3 = card("01928a3e-0003-7000-8000-000000000003", "Pontaポイント");

        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(card1, card2, card3), PageRequest.of(0, 1000), 3));

        when(providerMatchService.matchProvider("ドコモポイント")).thenReturn(Optional.of(provider()));
        when(providerMatchService.matchProvider("BAD"))
                .thenThrow(new RuntimeException("復号失敗をシミュレート"));
        when(providerMatchService.matchProvider("Pontaポイント")).thenReturn(Optional.of(provider()));

        service.execute();

        verify(userPointCardRepository, times(2)).save(any(UserPointCardEntity.class));
        assertMetadataContains("\"total\":3", "\"matched\":2", "\"skipped\":1");
        // 33% > 10% なので Sentry 通知される
        verify(errorReportService, times(1))
                .recordBackendException(any(Throwable.class), isNull(), eq(ErrorReportSeverity.HIGH));
    }

    // ──────────────────────────────────────────────
    // 5. 空テーブル
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("0 件 → no-op、集計 {total:0, matched:0, skipped:0} を発火")
    void emptyTable() {
        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 1000), 0));

        service.execute();

        verify(userPointCardRepository, never()).save(any(UserPointCardEntity.class));
        verify(providerMatchService, never()).matchProvider(any());
        assertMetadataContains("\"total\":0", "\"matched\":0", "\"skipped\":0");
        verify(errorReportService, never())
                .recordBackendException(any(Throwable.class), any(HttpServletRequest.class), any(ErrorReportSeverity.class));
    }

    // ──────────────────────────────────────────────
    // 6. チャンク境界
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("chunk-size=2 で 5 件 → 3 ページ取得（2+2+1）、全件処理確認")
    void chunkBoundary() {
        ReflectionTestUtils.setField(service, "chunkSize", 2);

        UserPointCardEntity c1 = card("01928a3e-0001-7000-8000-000000000001", "n1");
        UserPointCardEntity c2 = card("01928a3e-0002-7000-8000-000000000002", "n2");
        UserPointCardEntity c3 = card("01928a3e-0003-7000-8000-000000000003", "n3");
        UserPointCardEntity c4 = card("01928a3e-0004-7000-8000-000000000004", "n4");
        UserPointCardEntity c5 = card("01928a3e-0005-7000-8000-000000000005", "n5");

        // PageImpl(content, pageable, totalElements) で isLast() は自動算出される
        Page<UserPointCardEntity> p0 = new PageImpl<>(List.of(c1, c2), PageRequest.of(0, 2), 5);
        Page<UserPointCardEntity> p1 = new PageImpl<>(List.of(c3, c4), PageRequest.of(1, 2), 5);
        Page<UserPointCardEntity> p2 = new PageImpl<>(List.of(c5), PageRequest.of(2, 2), 5);

        when(userPointCardRepository.findRematchTargets(PageRequest.of(0, 2))).thenReturn(p0);
        when(userPointCardRepository.findRematchTargets(PageRequest.of(1, 2))).thenReturn(p1);
        when(userPointCardRepository.findRematchTargets(PageRequest.of(2, 2))).thenReturn(p2);

        when(providerMatchService.matchProvider(any())).thenReturn(Optional.empty());

        service.execute();

        // 3 回 findRematchTargets が呼ばれることを確認
        verify(userPointCardRepository, times(1)).findRematchTargets(PageRequest.of(0, 2));
        verify(userPointCardRepository, times(1)).findRematchTargets(PageRequest.of(1, 2));
        verify(userPointCardRepository, times(1)).findRematchTargets(PageRequest.of(2, 2));
        // 全 5 件 matchProvider 呼出
        verify(providerMatchService, times(5)).matchProvider(any());
        assertMetadataContains("\"total\":5", "\"matched\":0", "\"skipped\":0");
    }

    // ──────────────────────────────────────────────
    // 7. 高失敗率 → Sentry 通知
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("10 件中 2 件失敗（20%）→ ErrorReportService.recordBackendException(HIGH) が 1 回")
    void highFailureTriggersSentry() {
        List<UserPointCardEntity> cards = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            cards.add(card(String.format("01928a3e-%04d-7000-8000-000000000000", i), "n" + i));
        }
        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards, PageRequest.of(0, 1000), 10));

        // n1, n2 が失敗、残り 8 件は未マッチ（matched=0, skipped=2 で失敗率 20%）
        when(providerMatchService.matchProvider("n1")).thenThrow(new RuntimeException("fail1"));
        when(providerMatchService.matchProvider("n2")).thenThrow(new RuntimeException("fail2"));
        for (int i = 3; i <= 10; i++) {
            when(providerMatchService.matchProvider("n" + i)).thenReturn(Optional.empty());
        }

        service.execute();

        assertMetadataContains("\"total\":10", "\"skipped\":2");
        verify(errorReportService, times(1))
                .recordBackendException(any(Throwable.class), isNull(), eq(ErrorReportSeverity.HIGH));
    }

    // ──────────────────────────────────────────────
    // 8. 低失敗率 → Sentry 通知なし
    // ──────────────────────────────────────────────
    @Test
    @DisplayName("100 件中 5 件失敗（5%）→ ErrorReportService 呼び出しゼロ")
    void lowFailureNoSentry() {
        List<UserPointCardEntity> cards = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            cards.add(card(String.format("01928a3e-%04d-7000-8000-000000000000", i), "n" + i));
        }
        when(userPointCardRepository.findRematchTargets(any(Pageable.class)))
                .thenReturn(new PageImpl<>(cards, PageRequest.of(0, 1000), 100));

        // n1〜n5 失敗、残り 95 件は未マッチ（5%）
        for (int i = 1; i <= 5; i++) {
            when(providerMatchService.matchProvider("n" + i)).thenThrow(new RuntimeException("fail" + i));
        }
        for (int i = 6; i <= 100; i++) {
            when(providerMatchService.matchProvider("n" + i)).thenReturn(Optional.empty());
        }

        service.execute();

        assertMetadataContains("\"total\":100", "\"skipped\":5");
        verify(errorReportService, never())
                .recordBackendException(any(Throwable.class), any(HttpServletRequest.class), any(ErrorReportSeverity.class));
        verify(errorReportService, never())
                .recordBackendException(any(Throwable.class), isNull(), any(ErrorReportSeverity.class));
    }

    // ──────────────────────────────────────────────
    // ヘルパー
    // ──────────────────────────────────────────────

    private static UserPointCardEntity card(String uuid, String displayName) {
        UserPointCardEntity e = UserPointCardEntity.builder()
                .userId(1L)
                .displayName(displayName)
                .barcodeValue("0000000000")
                .build();
        e.setId(UUID.fromString(uuid));
        return e;
    }

    private static PointCardProviderEntity provider() {
        PointCardProviderEntity p = PointCardProviderEntity.builder().build();
        p.setId(UUID.randomUUID());
        return p;
    }

    /**
     * AuditLogService.record の metadata 引数を検査するヘルパー。
     */
    private void assertMetadataContains(String... expectedFragments) {
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
                eventTypeCaptor.capture(),
                isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(),
                metadataCaptor.capture());
        assertThat(eventTypeCaptor.getValue())
                .isEqualTo(AuditEventType.POINT_CARD_REMATCH_BATCH_EXECUTED.name());
        String md = metadataCaptor.getValue();
        for (String fragment : expectedFragments) {
            assertThat(md).as("metadata should contain %s", fragment).contains(fragment);
        }
        // durationMs も含まれていること
        assertThat(md).contains("\"durationMs\":");
    }
}
