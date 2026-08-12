package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.repository.AdReportScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportDeliveryBatchService} のユニットテスト（CMP-035）。
 *
 * <p>1件分の実データ集計・送信ロジックは {@link ReportSingleDeliveryService} へ分離済みのため、
 * ここでは「対象スケジュールの走査」「1件ごとの {@link ReportDeliveryRunner} への委譲」
 * 「1件の失敗が他の件の処理を止めないこと」の3点に絞って検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportDeliveryBatchService（広告レポート配信バッチ）")
class ReportDeliveryBatchServiceTest {

    @Mock private AdReportScheduleRepository adReportScheduleRepository;
    @Mock private ReportDeliveryRunner reportDeliveryRunner;

    private ReportDeliveryBatchService service;

    @BeforeEach
    void setUp() {
        service = new ReportDeliveryBatchService(adReportScheduleRepository, reportDeliveryRunner);
    }

    private AdReportScheduleEntity schedule(Long id) {
        return AdReportScheduleEntity.builder()
                .id(id)
                .advertiserAccountId(42L)
                .frequency(ReportFrequency.WEEKLY)
                .recipients("[\"advertiser@example.com\"]")
                .enabled(true)
                .createdBy(1L)
                .build();
    }

    @Test
    @DisplayName("対象スケジュールが無ければ何も配信しない")
    void 対象スケジュールなしは何もしない() {
        given(adReportScheduleRepository.findByEnabledTrueAndFrequency(ReportFrequency.MONTHLY))
                .willReturn(List.of());

        service.deliverMonthlyReports();

        verify(reportDeliveryRunner, never()).deliverOne(any(), any(), any());
    }

    @Test
    @DisplayName("対象スケジュール1件につき deliverOne を1回ずつ呼ぶ")
    void 対象スケジュールごとにdeliverOneを呼ぶ() {
        AdReportScheduleEntity s1 = schedule(1L);
        AdReportScheduleEntity s2 = schedule(2L);
        given(adReportScheduleRepository.findByEnabledTrueAndFrequency(ReportFrequency.WEEKLY))
                .willReturn(List.of(s1, s2));

        service.deliverWeeklyReports();

        verify(reportDeliveryRunner).deliverOne(eq(1L), any(LocalDate.class), any(LocalDate.class));
        verify(reportDeliveryRunner).deliverOne(eq(2L), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @DisplayName("途中の1件が例外を投げても、後続のスケジュールは処理を継続する")
    void 途中の1件が失敗しても後続は処理を継続する() {
        AdReportScheduleEntity broken = schedule(1L);
        AdReportScheduleEntity ok = schedule(2L);
        given(adReportScheduleRepository.findByEnabledTrueAndFrequency(ReportFrequency.WEEKLY))
                .willReturn(List.of(broken, ok));
        willThrow(new RuntimeException("配信失敗"))
                .given(reportDeliveryRunner).deliverOne(eq(1L), any(), any());

        service.deliverWeeklyReports();

        verify(reportDeliveryRunner).deliverOne(eq(1L), any(), any());
        verify(reportDeliveryRunner).deliverOne(eq(2L), any(), any());
    }
}
