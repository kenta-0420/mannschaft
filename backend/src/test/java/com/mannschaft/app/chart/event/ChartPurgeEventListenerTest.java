package com.mannschaft.app.chart.event;

import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartPurgeEventListener 単体テスト")
class ChartPurgeEventListenerTest {

    @Mock
    private ChartRecordRepository chartRecordRepository;

    @InjectMocks
    private ChartPurgeEventListener listener;

    private static final Long USER_ID = 300L;

    @Test
    @DisplayName("正常系: anonymizeCustomerUserId が呼ばれ匿名化件数が記録される")
    void 正常_匿名化実行() {
        given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(7);

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(chartRecordRepository, times(1)).anonymizeCustomerUserId(USER_ID);
    }

    @Test
    @DisplayName("正常系: 匿名化対象 0 件でも正常完了（例外なし）")
    void 正常_0件() {
        given(chartRecordRepository.anonymizeCustomerUserId(USER_ID)).willReturn(0);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(chartRecordRepository, times(1)).anonymizeCustomerUserId(USER_ID);
    }

    @Test
    @DisplayName("異常系: Repository 例外でも伝播せず WARN ログのみ")
    void 異常_例外_伝播せず() {
        willThrow(new RuntimeException("DB error"))
                .given(chartRecordRepository).anonymizeCustomerUserId(USER_ID);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(chartRecordRepository, times(1)).anonymizeCustomerUserId(USER_ID);
    }
}
