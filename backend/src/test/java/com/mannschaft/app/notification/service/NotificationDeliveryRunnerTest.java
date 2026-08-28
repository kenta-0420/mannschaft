package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 型確立PR — {@link NotificationDeliveryRunner} のユニットテスト。
 *
 * <h2>AC-4 の裏付け（deny と成功の区別）</h2>
 * <p>{@code createNotification} が visibility deny で {@code null} を返した場合、
 * {@link NotificationDeliveryRunner#sendOne} は {@link NotificationDeliveryResult#VISIBILITY_DENIED}
 * を返し、かつ {@link NotificationDispatchService#dispatch} を呼ばないことを検証する。</p>
 *
 * <h2>Issue #2959: 戻り値の非 Entity 化</h2>
 * <p>戻り値は {@code NotificationEntity} から {@link NotificationDeliveryResult} enum へ置き換えた
 * （呼び出し元は null 判定にしか使っておらず Entity を返す必然性が無かったため）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDeliveryRunner ユニットテスト")
class NotificationDeliveryRunnerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService notificationDispatchService;

    @InjectMocks
    private NotificationDeliveryRunner runner;

    private NotificationDeliveryRequest buildRequest() {
        return new NotificationDeliveryRequest(
                1L, "TEST_TYPE", NotificationPriority.NORMAL,
                "件名", "本文", "TEST_SOURCE", 10L,
                NotificationScopeType.PERSONAL, 1L, "/test", 2L);
    }

    @Test
    @DisplayName("成功時: createNotification が Entity を返せば DELIVERED を返し、dispatch を呼ぶ")
    void 成功時はdispatchを呼びDELIVEREDを返す() {
        NotificationEntity created = NotificationEntity.builder().userId(1L).build();
        given(notificationService.createNotification(
                anyLong(), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(),
                any(NotificationScopeType.class), anyLong(), anyString(), anyLong()))
                .willReturn(created);

        NotificationDeliveryResult result = runner.sendOne(buildRequest());

        assertThat(result).isEqualTo(NotificationDeliveryResult.DELIVERED);
        verify(notificationDispatchService).dispatch(created);
    }

    @Test
    @DisplayName("AC-4: visibility deny（null復帰）の場合、dispatch を呼ばず VISIBILITY_DENIED を返す")
    void denyの場合はdispatchを呼ばずVISIBILITY_DENIEDを返す() {
        given(notificationService.createNotification(
                anyLong(), anyString(), any(NotificationPriority.class),
                anyString(), anyString(), anyString(), anyLong(),
                any(NotificationScopeType.class), anyLong(), anyString(), anyLong()))
                .willReturn(null);

        NotificationDeliveryResult result = runner.sendOne(buildRequest());

        assertThat(result).isEqualTo(NotificationDeliveryResult.VISIBILITY_DENIED);
        verify(notificationDispatchService, never()).dispatch(any());
    }
}
