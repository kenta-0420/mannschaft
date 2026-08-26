package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxNotificationTypes;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link NotificationInboxAdapter} 単体テスト（Mockito）。
 *
 * <p>Phase3 ②（03_business_logic.md §5）の受け入れ条件: スヌーズ復帰 push
 * （{@code notification_type = INBOX_SNOOZE_REVIVAL}）はインボックス受信箱に
 * 再流入させない＝アダプタは「当該種別を除外する」クエリを使う（自己増殖回避）。</p>
 *
 * <p>Phase3 ③（§4.1）の取りこぼし根治: アダプタは created_at 降順のみの旧クエリではなく
 * <b>priority 第一順クエリ</b>（{@code findInboxByUserIdOrderByPriorityThenCreatedAtDesc}）を使い、
 * fetch 順をグローバル全順序に一致させる。これにより「古いが高 priority の通知」が window 外へ
 * 脱落する欠落を根絶する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationInboxAdapter 単体テスト")
class NotificationInboxAdapterTest {

    private static final Long USER_ID = 1L;

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    private final NotificationInboxAdapter adapter =
            new NotificationInboxAdapter(notificationRepository, normalizer,
                    new com.mannschaft.app.inbox.service.InboxDedupeKeyResolver());

    private NotificationEntity notification(Long id, String title) {
        return notification(id, title, NotificationPriority.NORMAL, LocalDateTime.now());
    }

    private NotificationEntity notification(Long id, String title, NotificationPriority priority,
                                            LocalDateTime createdAt) {
        return NotificationEntity.builder()
                .id(id)
                .userId(USER_ID)
                .notificationType("GENERIC")
                .priority(priority)
                .title(title)
                .body("body")
                .actionUrl("/x/" + id)
                .isRead(false)
                .createdAt(createdAt)
                .build();
    }

    @Test
    @DisplayName("fetch は priority 第一順クエリで INBOX_SNOOZE_REVIVAL 種別を除外する（自己増殖回避＋取りこぼし根治）")
    void fetch_usesPriorityFirstQueryExcludingSnoozeRevivalType() {
        Page<NotificationEntity> page = new PageImpl<>(List.of(notification(100L, "t")));
        given(notificationRepository.findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                eq(USER_ID), eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL), any(Pageable.class)))
                .willReturn(page);

        List<InboxItemDto> result = adapter.fetch(USER_ID, 50);

        // 除外種別を渡した priority 第一順クエリが呼ばれている
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationRepository).findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                eq(USER_ID), typeCaptor.capture(), any(Pageable.class));
        assertThat(typeCaptor.getValue()).isEqualTo(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sourceType()).isEqualTo(InboxSourceType.NOTIFICATION);
        assertThat(result.get(0).sourceId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("取りこぼし根治: 古いが URGENT の通知がクエリ結果先頭にあれば、新しい NORMAL 多数より先に出る")
    void fetch_oldUrgentSurfacesBeforeNewerNormal() {
        // DB の priority 第一順クエリは「古いが URGENT」を先頭に、その後に「新しい NORMAL」を返す
        // （findInboxByUserIdOrderByPriorityThenCreatedAtDesc の ORDER BY 契約）。
        // アダプタはこの順序を保ったまま DTO 化することを検証する（順序の破壊・並べ替えをしない）。
        LocalDateTime old = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime recent = LocalDateTime.of(2026, 6, 1, 0, 0);
        Page<NotificationEntity> page = new PageImpl<>(List.of(
                notification(500L, "古いが緊急", NotificationPriority.URGENT, old),
                notification(600L, "新しいが通常", NotificationPriority.NORMAL, recent),
                notification(601L, "新しいが通常2", NotificationPriority.NORMAL, recent.minusMinutes(1))));
        given(notificationRepository.findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                eq(USER_ID), eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL), any(Pageable.class)))
                .willReturn(page);

        List<InboxItemDto> result = adapter.fetch(USER_ID, 50);

        // priority 写像が InboxPriorityNormalizer.mapNotification と一致し URGENT が解決される。
        assertThat(result.get(0).priority()).isEqualTo(InboxPriority.URGENT);
        assertThat(result.get(0).sourceId()).isEqualTo(500L);
        // 取得順を温存（並べ替えはアダプタでなく集約 ITEM_ORDER の責務。アダプタは DB 順を保つ）。
        assertThat(result).extracting(InboxItemDto::sourceId)
                .containsExactly(500L, 600L, 601L);
    }

    @Test
    @DisplayName("fetch は window 件を超えて取得しない（PageRequest size <= window）")
    void fetch_boundsByWindow() {
        Page<NotificationEntity> page = new PageImpl<>(List.of(notification(100L, "t")));
        given(notificationRepository.findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                eq(USER_ID), eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL), any(Pageable.class)))
                .willReturn(page);

        adapter.fetch(USER_ID, 25);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                eq(USER_ID), eq(InboxNotificationTypes.INBOX_SNOOZE_REVIVAL), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isLessThanOrEqualTo(25);
    }

    @Test
    @DisplayName("window <= 0 は DB を引かず空を返す（無制限 fetch 根絶の境界）")
    void fetch_zeroWindowReturnsEmpty() {
        assertThat(adapter.fetch(USER_ID, 0)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(notificationRepository);
    }
}
