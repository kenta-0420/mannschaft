package com.mannschaft.app.publicview.event;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.metrics.PublicViewMetricsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SupporterNameDisclosureChangedEventListener} の単体テスト。
 *
 * <p>F19.1 Phase 5: モード変更イベント受信時のメトリクス記録を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupporterNameDisclosureChangedEventListener 単体テスト")
class SupporterNameDisclosureChangedEventListenerTest {

    @Mock
    private PublicViewMetricsService metricsService;

    @InjectMocks
    private SupporterNameDisclosureChangedEventListener listener;

    @Test
    @DisplayName("チームのモード変更イベント受信時: scope_type=TEAM でメトリクスが記録されること")
    void handleSupporterNameDisclosureChanged_teamMode_recordsMetrics() {
        // arrange: チームID あり、組織ID なし
        SupporterNameDisclosureChangedEvent event = new SupporterNameDisclosureChangedEvent(
                10L,
                null,
                NameDisclosureMode.DISPLAY_NAME,
                NameDisclosureMode.REAL_NAME,
                99L
        );

        // act
        listener.handleSupporterNameDisclosureChanged(event);

        // assert: TEAM スコープで recordModeChange が呼ばれること
        verify(metricsService, times(1)).recordModeChange(
                NameDisclosureMode.DISPLAY_NAME,
                NameDisclosureMode.REAL_NAME,
                "TEAM"
        );
    }

    @Test
    @DisplayName("組織のモード変更イベント受信時: scope_type=ORGANIZATION でメトリクスが記録されること")
    void handleSupporterNameDisclosureChanged_organizationMode_recordsMetrics() {
        // arrange: チームID なし、組織ID あり
        SupporterNameDisclosureChangedEvent event = new SupporterNameDisclosureChangedEvent(
                null,
                20L,
                NameDisclosureMode.REAL_NAME,
                NameDisclosureMode.DISPLAY_NAME,
                99L
        );

        // act
        listener.handleSupporterNameDisclosureChanged(event);

        // assert: ORGANIZATION スコープで recordModeChange が呼ばれること
        verify(metricsService, times(1)).recordModeChange(
                NameDisclosureMode.REAL_NAME,
                NameDisclosureMode.DISPLAY_NAME,
                "ORGANIZATION"
        );
    }
}
