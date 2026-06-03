package com.mannschaft.app.notification;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.NotificationStatsResponse;
import com.mannschaft.app.notification.dto.SnoozeRequest;
import com.mannschaft.app.notification.dto.UnreadCountResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationService} の単体テスト。
 * 通知のCRUD・既読管理・スヌーズ・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationService 単体テスト")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Mock
    private NotificationMapper notificationMapper;

    /**
     * F00 Phase F セキュリティガード用の visibility checker (mock)。
     * 既存テストは「visibility 通過済み」を前提とするため、デフォルトで allow。
     */
    @Mock
    private ContentVisibilityChecker visibilityChecker;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUpVisibilityCheckerDefaults() {
        given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(true);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 100L;

    private NotificationEntity createUnreadNotification() {
        return NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType("SCHEDULE_REMINDER")
                .priority(NotificationPriority.NORMAL)
                .title("リマインド")
                .body("出欠未回答です")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType(NotificationScopeType.TEAM)
                .scopeId(5L)
                .actionUrl("/schedules/10")
                .actorId(2L)
                .build();
    }

    private NotificationEntity createReadNotification() {
        NotificationEntity entity = createUnreadNotification();
        entity.markAsRead();
        return entity;
    }

    private NotificationResponse createNotificationResponse() {
        return NotificationResponse.builder()
                .id(NOTIFICATION_ID)
                .userId(USER_ID)
                .notificationType("SCHEDULE_REMINDER")
                .priority("NORMAL")
                .title("リマインド")
                .body("出欠未回答です")
                .actionUrl("/schedules/10")
                .sourceType("SCHEDULE")
                .sourceId(10L)
                .scopeType("TEAM")
                .scopeId(5L)
                .actorId(2L)
                .isRead(false)
                .readAt(null)
                .channelsSent(null)
                .snoozedUntil(null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // listNotifications
    // ========================================

    @Nested
    @DisplayName("listNotifications")
    class ListNotifications {

        @Test
        @DisplayName("通知一覧取得_正常_ページ返却")
        void 通知一覧取得_正常_ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            NotificationEntity entity = createUnreadNotification();
            Page<NotificationEntity> page = new PageImpl<>(List.of(entity), pageable, 1);
            NotificationResponse response = createNotificationResponse();

            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
                    .willReturn(page);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // When
            Page<NotificationResponse> result = notificationService.listNotifications(USER_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getTitle()).isEqualTo("リマインド");
            verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(USER_ID, pageable);
        }

        @Test
        @DisplayName("通知一覧取得_通知なし_空ページ返却")
        void 通知一覧取得_通知なし_空ページ返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<NotificationEntity> emptyPage = new PageImpl<>(List.of(), pageable, 0);

            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
                    .willReturn(emptyPage);

            // When
            Page<NotificationResponse> result = notificationService.listNotifications(USER_ID, pageable);

            // Then
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("通知一覧取得_複数件_マッピングされて返却")
        void 通知一覧取得_複数件_マッピングされて返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            NotificationEntity entity1 = createUnreadNotification();
            NotificationEntity entity2 = createReadNotification();
            Page<NotificationEntity> page = new PageImpl<>(List.of(entity1, entity2), pageable, 2);
            NotificationResponse response = createNotificationResponse();

            given(notificationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
                    .willReturn(page);
            given(notificationMapper.toNotificationResponse(any(NotificationEntity.class)))
                    .willReturn(response);

            // When
            Page<NotificationResponse> result = notificationService.listNotifications(USER_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getTotalElements()).isEqualTo(2);
        }
    }

    // ========================================
    // getUnreadCount
    // ========================================

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("未読件数取得_正常_件数返却")
        void 未読件数取得_正常_件数返却() {
            // Given
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(5L);

            // When
            UnreadCountResponse result = notificationService.getUnreadCount(USER_ID);

            // Then
            assertThat(result.getUnreadCount()).isEqualTo(5L);
        }

        @Test
        @DisplayName("未読件数取得_未読なし_ゼロ返却")
        void 未読件数取得_未読なし_ゼロ返却() {
            // Given
            given(notificationRepository.countByUserIdAndIsReadFalse(USER_ID)).willReturn(0L);

            // When
            UnreadCountResponse result = notificationService.getUnreadCount(USER_ID);

            // Then
            assertThat(result.getUnreadCount()).isZero();
        }
    }

    // ========================================
    // markAsRead
    // ========================================

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("既読化_正常_既読状態に更新")
        void 既読化_正常_既読状態に更新() {
            // Given
            NotificationEntity entity = createUnreadNotification();
            NotificationResponse response = createNotificationResponse();

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(notificationRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // When
            NotificationResponse result = notificationService.markAsRead(USER_ID, NOTIFICATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(notificationRepository).save(entity);
        }

        @Test
        @DisplayName("既読化_既に既読_NOTIFICATION_006例外")
        void 既読化_既に既読_NOTIFICATION006例外() {
            // Given
            NotificationEntity entity = createReadNotification();

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> notificationService.markAsRead(USER_ID, NOTIFICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_006"));
        }

        @Test
        @DisplayName("既読化_通知不在_NOTIFICATION_001例外")
        void 既読化_通知不在_NOTIFICATION001例外() {
            // Given
            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.markAsRead(USER_ID, NOTIFICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_001"));
        }
    }

    // ========================================
    // markAsUnread
    // ========================================

    @Nested
    @DisplayName("markAsUnread")
    class MarkAsUnread {

        @Test
        @DisplayName("未読戻し_正常_未読状態に更新")
        void 未読戻し_正常_未読状態に更新() {
            // Given
            NotificationEntity entity = createReadNotification();
            NotificationResponse response = createNotificationResponse();

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(notificationRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // When
            NotificationResponse result = notificationService.markAsUnread(USER_ID, NOTIFICATION_ID);

            // Then
            assertThat(result).isNotNull();
            verify(notificationRepository).save(entity);
        }

        @Test
        @DisplayName("未読戻し_既に未読_NOTIFICATION_007例外")
        void 未読戻し_既に未読_NOTIFICATION007例外() {
            // Given
            NotificationEntity entity = createUnreadNotification();

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> notificationService.markAsUnread(USER_ID, NOTIFICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_007"));
        }

        @Test
        @DisplayName("未読戻し_通知不在_NOTIFICATION_001例外")
        void 未読戻し_通知不在_NOTIFICATION001例外() {
            // Given
            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.markAsUnread(USER_ID, NOTIFICATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_001"));
        }
    }

    // ========================================
    // snoozeNotification
    // ========================================

    @Nested
    @DisplayName("snoozeNotification")
    class SnoozeNotification {

        @Test
        @DisplayName("スヌーズ_正常_スヌーズ設定")
        void スヌーズ_正常_スヌーズ設定() {
            // Given
            NotificationEntity entity = createUnreadNotification();
            NotificationResponse response = createNotificationResponse();
            // JST(+09:00) で未来時刻を表す絶対時刻。JST 壁時計へ変換されて保存される。
            OffsetDateTime futureTime = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(1);
            LocalDateTime expectedJst =
                    futureTime.atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")).toLocalDateTime();
            SnoozeRequest request = new SnoozeRequest(futureTime);

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(notificationRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // When
            NotificationResponse result = notificationService.snoozeNotification(USER_ID, NOTIFICATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getSnoozedUntil()).isEqualTo(expectedJst);
            verify(notificationRepository).save(entity);
        }

        @Test
        @DisplayName("TZ根治_UTC入力_JST壁時計で保存")
        void スヌーズ_UTC入力_JST壁時計で保存() {
            // Given: UTC 未来時刻で JST に変換したときオフセットが正しく反映されることを検証。
            // 旧実装（LocalDateTime 受け）なら Z が捨てられ UTC の時刻そのままが保存されて赤くなる。
            // 固定日時は日付経過で過去になりフレーキーになるため、動的に「来週の月曜 14:00 UTC」を使用。
            NotificationEntity entity = createUnreadNotification();
            NotificationResponse response = createNotificationResponse();
            // 来週月曜 14:00 UTC == 来週月曜 23:00 JST（TZ変換の正しさを検証）
            java.time.LocalDate nextMonday = java.time.LocalDate.now().plusWeeks(1)
                    .with(java.time.DayOfWeek.MONDAY);
            OffsetDateTime utcInput = OffsetDateTime.of(nextMonday.getYear(), nextMonday.getMonthValue(),
                    nextMonday.getDayOfMonth(), 14, 0, 0, 0, ZoneOffset.UTC);
            LocalDateTime expectedJst = utcInput.atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"))
                    .toLocalDateTime();
            SnoozeRequest request = new SnoozeRequest(utcInput);

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));
            given(notificationRepository.save(entity)).willReturn(entity);
            given(notificationMapper.toNotificationResponse(entity)).willReturn(response);

            // When
            notificationService.snoozeNotification(USER_ID, NOTIFICATION_ID, request);

            // Then
            assertThat(entity.getSnoozedUntil()).isEqualTo(expectedJst);
        }

        @Test
        @DisplayName("スヌーズ_過去日時_NOTIFICATION_008例外")
        void スヌーズ_過去日時_NOTIFICATION008例外() {
            // Given
            NotificationEntity entity = createUnreadNotification();
            OffsetDateTime pastTime = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusHours(1);
            SnoozeRequest request = new SnoozeRequest(pastTime);

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> notificationService.snoozeNotification(USER_ID, NOTIFICATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_008"));
        }

        @Test
        @DisplayName("スヌーズ_通知不在_NOTIFICATION_001例外")
        void スヌーズ_通知不在_NOTIFICATION001例外() {
            // Given
            SnoozeRequest request = new SnoozeRequest(OffsetDateTime.now(ZoneOffset.ofHours(9)).plusHours(1));

            given(notificationRepository.findByIdAndUserId(NOTIFICATION_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> notificationService.snoozeNotification(USER_ID, NOTIFICATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_001"));
        }
    }

    // ========================================
    // markAllAsRead
    // ========================================

    @Nested
    @DisplayName("markAllAsRead")
    class MarkAllAsRead {

        @Test
        @DisplayName("全件既読_正常_更新件数返却")
        void 全件既読_正常_更新件数返却() {
            // Given
            given(notificationRepository.markAllAsReadByUserId(USER_ID)).willReturn(3);

            // When
            int count = notificationService.markAllAsRead(USER_ID);

            // Then
            assertThat(count).isEqualTo(3);
            verify(notificationRepository).markAllAsReadByUserId(USER_ID);
        }

        @Test
        @DisplayName("全件既読_未読なし_ゼロ返却")
        void 全件既読_未読なし_ゼロ返却() {
            // Given
            given(notificationRepository.markAllAsReadByUserId(USER_ID)).willReturn(0);

            // When
            int count = notificationService.markAllAsRead(USER_ID);

            // Then
            assertThat(count).isZero();
        }
    }

    // ========================================
    // createNotification
    // ========================================

    @Nested
    @DisplayName("createNotification")
    class CreateNotification {

        @Test
        @DisplayName("通知作成_正常_エンティティ返却")
        void 通知作成_正常_エンティティ返却() {
            // Given
            given(notificationRepository.save(any(NotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "SCHEDULE_REMINDER", NotificationPriority.NORMAL,
                    "リマインド", "出欠未回答です", "SCHEDULE", 10L,
                    NotificationScopeType.TEAM, 5L, "/schedules/10", 2L);

            // Then
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getNotificationType()).isEqualTo("SCHEDULE_REMINDER");
            assertThat(result.getPriority()).isEqualTo(NotificationPriority.NORMAL);
            assertThat(result.getTitle()).isEqualTo("リマインド");
            assertThat(result.getBody()).isEqualTo("出欠未回答です");
            assertThat(result.getScopeType()).isEqualTo(NotificationScopeType.TEAM);
            verify(notificationRepository).save(any(NotificationEntity.class));
        }

        @Test
        @DisplayName("通知作成_HIGH優先度_エンティティ返却")
        void 通知作成_HIGH優先度_エンティティ返却() {
            // Given
            given(notificationRepository.save(any(NotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "SYSTEM_ALERT", NotificationPriority.HIGH,
                    "緊急通知", "システムメンテナンス", "SYSTEM", null,
                    NotificationScopeType.PERSONAL, null, null, null);

            // Then
            assertThat(result.getPriority()).isEqualTo(NotificationPriority.HIGH);
            assertThat(result.getNotificationType()).isEqualTo("SYSTEM_ALERT");
            assertThat(result.getSourceId()).isNull();
            assertThat(result.getActorId()).isNull();
        }

        @Test
        @DisplayName("通知作成_組織スコープ_エンティティ返却")
        void 通知作成_組織スコープ_エンティティ返却() {
            // Given
            given(notificationRepository.save(any(NotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "ORG_UPDATE", NotificationPriority.LOW,
                    "組織更新", "組織情報が更新されました", "ORGANIZATION", 20L,
                    NotificationScopeType.ORGANIZATION, 20L, "/orgs/20", 3L);

            // Then
            assertThat(result.getScopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
            assertThat(result.getScopeId()).isEqualTo(20L);
            assertThat(result.getActionUrl()).isEqualTo("/orgs/20");
        }
    }

    // ========================================
    // createNotification（organizationId あり版）
    // ========================================

    @Nested
    @DisplayName("createNotification_organizationId")
    class CreateNotificationWithOrganizationId {

        @Test
        @DisplayName("organizationId あり_通知が組織IDつきで保存される")
        void organizationIdあり_通知が組織IDつきで保存される() {
            // Given
            Long organizationId = 99L;
            given(notificationRepository.save(any(NotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "ORG_ANNOUNCEMENT", NotificationPriority.NORMAL,
                    "組織からのお知らせ", "本文テスト", "ORGANIZATION", 99L,
                    NotificationScopeType.ORGANIZATION, 99L, "/orgs/99/announcements", 3L,
                    organizationId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOrganizationId()).isEqualTo(organizationId);
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            verify(notificationRepository).save(any(NotificationEntity.class));
        }

        @Test
        @DisplayName("organizationId なし_通知が保存される（後方互換）")
        void organizationIdなし_通知が保存される後方互換() {
            // Given
            given(notificationRepository.save(any(NotificationEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When（organizationId を受け取らない既存オーバーロードを呼ぶ）
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "SCHEDULE_REMINDER", NotificationPriority.NORMAL,
                    "リマインド", "出欠未回答です", "SCHEDULE", 10L,
                    NotificationScopeType.TEAM, 5L, "/schedules/10", 2L);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOrganizationId()).isNull();
            verify(notificationRepository).save(any(NotificationEntity.class));
        }

        @Test
        @DisplayName("organizationId あり_visibilityDeny_通知作成スキップ")
        void organizationIdあり_visibilityDeny_通知作成スキップ() {
            // Given（visibility 拒否）
            given(visibilityChecker.canView(any(ReferenceType.class), any(), any())).willReturn(false);

            // When
            NotificationEntity result = notificationService.createNotification(
                    USER_ID, "ORG_UPDATE", NotificationPriority.LOW,
                    "組織更新", "本文", "ORGANIZATION", 99L,
                    NotificationScopeType.ORGANIZATION, 99L, "/orgs/99", 3L,
                    99L);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("統計取得_正常_統計レスポンス返却")
        void 統計取得_正常_統計レスポンス返却() {
            // Given
            given(notificationRepository.count()).willReturn(100L);
            given(notificationRepository.countByIsReadFalse()).willReturn(30L);
            given(pushSubscriptionRepository.count()).willReturn(15L);

            // When
            NotificationStatsResponse result = notificationService.getStats();

            // Then
            assertThat(result.getTotalNotifications()).isEqualTo(100L);
            assertThat(result.getUnreadCount()).isEqualTo(30L);
            assertThat(result.getReadCount()).isEqualTo(70L);
            assertThat(result.getTotalSubscriptions()).isEqualTo(15L);
        }

        @Test
        @DisplayName("統計取得_データなし_ゼロ返却")
        void 統計取得_データなし_ゼロ返却() {
            // Given
            given(notificationRepository.count()).willReturn(0L);
            given(notificationRepository.countByIsReadFalse()).willReturn(0L);
            given(pushSubscriptionRepository.count()).willReturn(0L);

            // When
            NotificationStatsResponse result = notificationService.getStats();

            // Then
            assertThat(result.getTotalNotifications()).isZero();
            assertThat(result.getUnreadCount()).isZero();
            assertThat(result.getReadCount()).isZero();
            assertThat(result.getTotalSubscriptions()).isZero();
        }

        @Test
        @DisplayName("統計取得_全件既読_readCountがtotalに一致")
        void 統計取得_全件既読_readCountがtotalに一致() {
            // Given
            given(notificationRepository.count()).willReturn(50L);
            given(notificationRepository.countByIsReadFalse()).willReturn(0L);
            given(pushSubscriptionRepository.count()).willReturn(10L);

            // When
            NotificationStatsResponse result = notificationService.getStats();

            // Then
            assertThat(result.getReadCount()).isEqualTo(50L);
            assertThat(result.getUnreadCount()).isZero();
        }
    }
}
