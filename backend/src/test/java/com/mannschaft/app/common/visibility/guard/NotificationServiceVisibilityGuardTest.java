package com.mannschaft.app.common.visibility.guard;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F00 Phase F: {@link NotificationService#createNotification} の visibility ガードテスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §11.1 (Mention・通知配信での Resolver 必須利用) / §13.5 (Mockito InOrder
 * ガードテスト)。
 *
 * <p>本テストは「ContentVisibilityChecker.canView →
 * NotificationRepository.save の順序」を {@link InOrder} で検証し、
 * 通知作成前の visibility ガード忘れをランタイムで検出する。
 *
 * <p>位置付け: {@code NotificationVisibilityArchitectureTest} (静的依存検査)
 * と組み合わせて二段防御を実現する。ArchUnit は「依存があるか」しか見ない
 * ため、メソッド本体での呼び忘れ検出に本ガードテストが不可欠。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F00 Phase F: NotificationService 通知発行ガードテスト")
class NotificationServiceVisibilityGuardTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    @SuppressWarnings("unused") // NotificationService への DI のため必要 (テストでは未使用)
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Mock
    @SuppressWarnings("unused")
    private NotificationMapper notificationMapper;

    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private NotificationService notificationService;

    private static final Long USER_ID = 100L;
    private static final Long CONTENT_ID = 555L;

    @Test
    @DisplayName("createNotification: visibility allow → canView → save の順で呼ばれる")
    void createNotification_calls_visibility_checker_before_repository_save() {
        // Given: BLOG_POST に対する閲覧を allow
        given(visibilityChecker.canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID)))
                .willReturn(true);
        given(notificationRepository.save(any(NotificationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // When
        NotificationEntity result = notificationService.createNotification(
                USER_ID, "BLOG_MENTION", NotificationPriority.NORMAL,
                "メンション", "ブログでメンションされました",
                "BLOG_POST", CONTENT_ID,
                NotificationScopeType.PERSONAL, null,
                "/blog/" + CONTENT_ID, 1L);

        // Then: canView → save の順で呼ばれる
        InOrder order = inOrder(visibilityChecker, notificationRepository);
        order.verify(visibilityChecker, atLeastOnce())
                .canView(eq(ReferenceType.BLOG_POST), eq(CONTENT_ID), eq(USER_ID));
        order.verify(notificationRepository, atLeastOnce()).save(any(NotificationEntity.class));

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getSourceType()).isEqualTo("BLOG_POST");
    }

    @Test
    @DisplayName("createNotification: visibility deny → save が呼ばれず null 返却")
    void createNotification_skips_save_when_visibility_denied() {
        // Given: SCHEDULE への閲覧を deny
        given(visibilityChecker.canView(eq(ReferenceType.SCHEDULE), eq(CONTENT_ID), eq(USER_ID)))
                .willReturn(false);

        // When
        NotificationEntity result = notificationService.createNotification(
                USER_ID, "SCHEDULE_REMINDER", NotificationPriority.NORMAL,
                "リマインド", "出欠未回答",
                "SCHEDULE", CONTENT_ID,
                NotificationScopeType.TEAM, 5L,
                "/schedules/" + CONTENT_ID, null);

        // Then: save は呼ばれず null 返却
        verify(visibilityChecker).canView(eq(ReferenceType.SCHEDULE), eq(CONTENT_ID), eq(USER_ID));
        verify(notificationRepository, never()).save(any(NotificationEntity.class));
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("createNotification: ReferenceType 未対応 sourceType は visibility ガードを通過 (fail-soft)")
    void createNotification_passes_through_unmapped_source_type() {
        // Given: MEMBER_PAYMENT は ReferenceType に未登録 (NotificationSourceTypeMapper)
        given(notificationRepository.save(any(NotificationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // When
        NotificationEntity result = notificationService.createNotification(
                USER_ID, "PAYMENT_NOTICE", NotificationPriority.NORMAL,
                "支払通知", "会費が引き落としされました",
                "MEMBER_PAYMENT", CONTENT_ID,
                NotificationScopeType.PERSONAL, null,
                "/payments/" + CONTENT_ID, null);

        // Then: visibilityChecker.canView は呼ばれず、save は呼ばれる
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(notificationRepository).save(any(NotificationEntity.class));
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("createNotification: sourceId が null の場合は visibility ガードを通過 (fail-soft)")
    void createNotification_passes_through_when_source_id_is_null() {
        // Given
        given(notificationRepository.save(any(NotificationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // When
        NotificationEntity result = notificationService.createNotification(
                USER_ID, "SYSTEM_ALERT", NotificationPriority.HIGH,
                "システム通知", "メンテナンス予定",
                "BLOG_POST", null /* sourceId 不明 */,
                NotificationScopeType.SYSTEM, null,
                null, null);

        // Then: visibilityChecker は呼ばれない (sourceId null は判定対象外)
        verify(visibilityChecker, never()).canView(any(), any(), any());
        verify(notificationRepository).save(any(NotificationEntity.class));
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("createNotification: visibility deny の通知は ArgumentCaptor で検証可能 (実際は save 呼ばれない)")
    void createNotification_blocks_unauthorized_recipient() {
        // Given: 認可外ユーザーで TIMELINE_POST 通知を試行
        given(visibilityChecker.canView(eq(ReferenceType.TIMELINE_POST), eq(CONTENT_ID), eq(USER_ID)))
                .willReturn(false);

        // When
        NotificationEntity result = notificationService.createNotification(
                USER_ID, "TIMELINE_MENTION", NotificationPriority.NORMAL,
                "メンション", "投稿でメンションされました",
                "TIMELINE_POST", CONTENT_ID,
                NotificationScopeType.PERSONAL, null,
                "/timeline/" + CONTENT_ID, 1L);

        // Then: save 呼出 0 回
        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository, never()).save(captor.capture());
        assertThat(captor.getAllValues()).isEmpty();
        assertThat(result).isNull();
    }
}
