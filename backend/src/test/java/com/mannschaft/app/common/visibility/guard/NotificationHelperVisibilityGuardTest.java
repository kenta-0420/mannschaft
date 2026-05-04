package com.mannschaft.app.common.visibility.guard;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F00 Phase F: {@link NotificationHelper#notifyAll} の visibility ガードテスト。
 *
 * <p>受信者リスト確定後に {@link ContentVisibilityChecker} を通すこと、
 * 閲覧不可ユーザーには通知が作成されないことを Mockito InOrder + ArgumentCaptor
 * で検証する (設計書 §11.1 / §13.5)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F00 Phase F: NotificationHelper 一括通知ガードテスト")
class NotificationHelperVisibilityGuardTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private NotificationDispatchService dispatchService;

    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private NotificationHelper notificationHelper;

    private static final String SOURCE_TYPE = "BLOG_POST";
    private static final Long CONTENT_ID = 555L;
    private static final String NOTIFICATION_TYPE = "BLOG_MENTION";

    private NotificationEntity stubEntity(Long userId) {
        return NotificationEntity.builder()
                .userId(userId)
                .notificationType(NOTIFICATION_TYPE)
                .priority(NotificationPriority.NORMAL)
                .title("メンション")
                .body("ブログでメンションされました")
                .sourceType(SOURCE_TYPE)
                .sourceId(CONTENT_ID)
                .scopeType(NotificationScopeType.PERSONAL)
                .build();
    }

    @Test
    @DisplayName("notifyAll: visibility allow ユーザーのみ作成・配信される (canView → createNotification 順)")
    void notifyAll_filters_out_non_accessible_users() {
        // Given: user 2 のみ deny
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(1L)))
                .willReturn(true);
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(2L)))
                .willReturn(false);
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(3L)))
                .willReturn(true);

        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .willAnswer(inv -> stubEntity(inv.getArgument(0, Long.class)));

        // When
        notificationHelper.notifyAll(
                List.of(1L, 2L, 3L), NOTIFICATION_TYPE, "メンション", "本文",
                SOURCE_TYPE, CONTENT_ID, NotificationScopeType.PERSONAL, null,
                "/blog/" + CONTENT_ID, 99L);

        // Then: createNotification は 1L と 3L についてのみ呼ばれる (合計 2 回)
        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, times(2)).createNotification(
                userIdCaptor.capture(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        assertThat(userIdCaptor.getAllValues()).containsExactlyInAnyOrder(1L, 3L);

        // user 2 については canView が呼ばれた後 createNotification は呼ばれない
        verify(visibilityChecker).canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(2L));
        verify(dispatchService, times(2)).dispatch(any());
    }

    @Test
    @DisplayName("notifyAll: canView → createNotification → dispatch の順序が守られる (InOrder)")
    void notifyAll_order_canView_create_dispatch() {
        // Given
        given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(true);
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .willAnswer(inv -> stubEntity(inv.getArgument(0, Long.class)));

        // When
        notificationHelper.notifyAll(
                List.of(1L), NOTIFICATION_TYPE, "メンション", "本文",
                SOURCE_TYPE, CONTENT_ID, NotificationScopeType.PERSONAL, null,
                "/blog/" + CONTENT_ID, 99L);

        // Then: visibility checker → createNotification → dispatch の順
        InOrder order = inOrder(visibilityChecker, notificationService, dispatchService);
        order.verify(visibilityChecker, atLeastOnce())
                .canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(1L));
        order.verify(notificationService).createNotification(
                eq(1L), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        order.verify(dispatchService).dispatch(any());
    }

    @Test
    @DisplayName("notifyAll: 全員 deny → createNotification 呼ばれず")
    void notifyAll_all_denied_skips_creation() {
        // Given
        given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(false);

        // When
        notificationHelper.notifyAll(
                List.of(1L, 2L, 3L), NOTIFICATION_TYPE, "メンション", "本文",
                SOURCE_TYPE, CONTENT_ID, NotificationScopeType.PERSONAL, null,
                "/blog/" + CONTENT_ID, 99L);

        // Then
        verify(notificationService, never()).createNotification(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    @DisplayName("notifyAll: ReferenceType 未対応 sourceType (MEMBER_PAYMENT) は全員に通知 (fail-soft)")
    void notifyAll_passes_through_unmapped_source_type() {
        // Given: MEMBER_PAYMENT 未マップ → visibility は呼ばれず全員通知
        given(notificationService.createNotification(
                anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .willAnswer(inv -> stubEntity(inv.getArgument(0, Long.class)));

        // When
        notificationHelper.notifyAll(
                List.of(1L, 2L, 3L), "PAYMENT_NOTICE", "支払通知", "本文",
                "MEMBER_PAYMENT", CONTENT_ID, NotificationScopeType.PERSONAL, null,
                "/payments/" + CONTENT_ID, null);

        // Then
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(notificationService, times(3)).createNotification(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyAll: 空リストは何もしない")
    void notifyAll_empty_list_skips() {
        // When
        notificationHelper.notifyAll(
                List.of(), NOTIFICATION_TYPE, "メンション", "本文",
                SOURCE_TYPE, CONTENT_ID, NotificationScopeType.PERSONAL, null,
                "/blog/" + CONTENT_ID, 99L);

        // Then
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(notificationService, never()).createNotification(
                any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }
}
