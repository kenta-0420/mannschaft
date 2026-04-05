package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationMapper}（MapStruct生成実装）の単体テスト。
 * Entity → DTO の変換ロジックを検証する。
 */
@DisplayName("NotificationMapper 単体テスト")
class NotificationMapperTest {

    private NotificationMapper notificationMapper;

    @BeforeEach
    void setUp() {
        notificationMapper = new NotificationMapperImpl();
    }

    // ========================================
    // toNotificationResponse
    // ========================================

    @Nested
    @DisplayName("toNotificationResponse")
    class ToNotificationResponse {

        @Test
        @DisplayName("正常系: NotificationEntityがNotificationResponseに変換される")
        void toNotificationResponse_正常_DTOに変換() {
            // Given
            NotificationEntity entity = NotificationEntity.builder()
                    .userId(1L)
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
            ReflectionTestUtils.setField(entity, "id", 100L);

            // When
            NotificationResponse response = notificationMapper.toNotificationResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(100L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getNotificationType()).isEqualTo("SCHEDULE_REMINDER");
            assertThat(response.getPriority()).isEqualTo("NORMAL");
            assertThat(response.getTitle()).isEqualTo("リマインド");
            assertThat(response.getBody()).isEqualTo("出欠未回答です");
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(5L);
            assertThat(response.getIsRead()).isFalse();
        }

        @Test
        @DisplayName("正常系: HIGH優先度通知が変換される")
        void toNotificationResponse_HIGH優先度_DTOに変換() {
            // Given
            NotificationEntity entity = NotificationEntity.builder()
                    .userId(1L)
                    .notificationType("SYSTEM_ALERT")
                    .priority(NotificationPriority.HIGH)
                    .title("重要通知")
                    .body("重要なお知らせがあります")
                    .sourceType("SYSTEM")
                    .scopeType(NotificationScopeType.SYSTEM)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 101L);

            // When
            NotificationResponse response = notificationMapper.toNotificationResponse(entity);

            // Then
            assertThat(response.getPriority()).isEqualTo("HIGH");
            assertThat(response.getScopeType()).isEqualTo("SYSTEM");
        }

        @Test
        @DisplayName("正常系: URGENT優先度・ORGANIZATION スコープが変換される")
        void toNotificationResponse_URGENT_ORGANIZATION_DTOに変換() {
            // Given
            NotificationEntity entity = NotificationEntity.builder()
                    .userId(2L)
                    .notificationType("ORG_ALERT")
                    .priority(NotificationPriority.URGENT)
                    .title("緊急通知")
                    .body("緊急のお知らせ")
                    .sourceType("ORGANIZATION")
                    .scopeType(NotificationScopeType.ORGANIZATION)
                    .scopeId(20L)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 102L);

            // When
            NotificationResponse response = notificationMapper.toNotificationResponse(entity);

            // Then
            assertThat(response.getPriority()).isEqualTo("URGENT");
            assertThat(response.getScopeType()).isEqualTo("ORGANIZATION");
        }

        @Test
        @DisplayName("正常系: 既読通知が変換される")
        void toNotificationResponse_既読通知_DTOに変換() {
            // Given
            NotificationEntity entity = NotificationEntity.builder()
                    .userId(1L)
                    .notificationType("CHAT_MESSAGE")
                    .priority(NotificationPriority.NORMAL)
                    .title("新着メッセージ")
                    .body("メッセージが届きました")
                    .sourceType("CHAT")
                    .scopeType(NotificationScopeType.TEAM)
                    .scopeId(5L)
                    .build();
            entity.markAsRead();
            ReflectionTestUtils.setField(entity, "id", 103L);

            // When
            NotificationResponse response = notificationMapper.toNotificationResponse(entity);

            // Then
            assertThat(response.getIsRead()).isTrue();
            assertThat(response.getReadAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: PERSONAL スコープが変換される")
        void toNotificationResponse_PERSONAL_DTOに変換() {
            // Given
            NotificationEntity entity = NotificationEntity.builder()
                    .userId(1L)
                    .notificationType("PERSONAL")
                    .priority(NotificationPriority.LOW)
                    .title("個人通知")
                    .body("個人向けのお知らせ")
                    .sourceType("PERSONAL")
                    .scopeType(NotificationScopeType.PERSONAL)
                    .scopeId(1L)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 104L);

            // When
            NotificationResponse response = notificationMapper.toNotificationResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("PERSONAL");
            assertThat(response.getPriority()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("正常系: 通知リストが変換される")
        void toNotificationResponseList_正常_リスト変換() {
            // Given
            NotificationEntity e1 = NotificationEntity.builder()
                    .userId(1L).notificationType("TYPE_A")
                    .priority(NotificationPriority.NORMAL).title("通知A")
                    .body("本文A").sourceType("SRC").scopeType(NotificationScopeType.TEAM).build();
            NotificationEntity e2 = NotificationEntity.builder()
                    .userId(2L).notificationType("TYPE_B")
                    .priority(NotificationPriority.HIGH).title("通知B")
                    .body("本文B").sourceType("SRC").scopeType(NotificationScopeType.ORGANIZATION).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<NotificationResponse> responses = notificationMapper.toNotificationResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTitle()).isEqualTo("通知A");
            assertThat(responses.get(1).getPriority()).isEqualTo("HIGH");
        }
    }

    // ========================================
    // toPreferenceResponse
    // ========================================

    @Nested
    @DisplayName("toPreferenceResponse")
    class ToPreferenceResponse {

        @Test
        @DisplayName("正常系: NotificationPreferenceEntityがPreferenceResponseに変換される")
        void toPreferenceResponse_正常_DTOに変換() {
            // Given
            NotificationPreferenceEntity entity = NotificationPreferenceEntity.builder()
                    .userId(1L)
                    .scopeType("TEAM")
                    .scopeId(5L)
                    .isEnabled(true)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 10L);

            // When
            PreferenceResponse response = notificationMapper.toPreferenceResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(10L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(5L);
            assertThat(response.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("正常系: 無効化された通知設定が変換される")
        void toPreferenceResponse_無効化_DTOに変換() {
            // Given
            NotificationPreferenceEntity entity = NotificationPreferenceEntity.builder()
                    .userId(1L)
                    .scopeType("ORGANIZATION")
                    .scopeId(20L)
                    .isEnabled(false)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 11L);

            // When
            PreferenceResponse response = notificationMapper.toPreferenceResponse(entity);

            // Then
            assertThat(response.getIsEnabled()).isFalse();
        }

        @Test
        @DisplayName("正常系: 通知設定リストが変換される")
        void toPreferenceResponseList_正常_リスト変換() {
            // Given
            NotificationPreferenceEntity e1 = NotificationPreferenceEntity.builder()
                    .userId(1L).scopeType("TEAM").scopeId(5L).isEnabled(true).build();
            NotificationPreferenceEntity e2 = NotificationPreferenceEntity.builder()
                    .userId(1L).scopeType("ORGANIZATION").scopeId(10L).isEnabled(false).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<PreferenceResponse> responses = notificationMapper.toPreferenceResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getIsEnabled()).isTrue();
            assertThat(responses.get(1).getIsEnabled()).isFalse();
        }
    }

    // ========================================
    // toTypePreferenceResponse
    // ========================================

    @Nested
    @DisplayName("toTypePreferenceResponse")
    class ToTypePreferenceResponse {

        @Test
        @DisplayName("正常系: NotificationTypePreferenceEntityがTypePreferenceResponseに変換される")
        void toTypePreferenceResponse_正常_DTOに変換() {
            // Given
            NotificationTypePreferenceEntity entity = NotificationTypePreferenceEntity.builder()
                    .userId(1L)
                    .notificationType("SCHEDULE_REMINDER")
                    .isEnabled(true)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 20L);

            // When
            TypePreferenceResponse response = notificationMapper.toTypePreferenceResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(20L);
            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getNotificationType()).isEqualTo("SCHEDULE_REMINDER");
            assertThat(response.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("正常系: 無効化された通知種別設定が変換される")
        void toTypePreferenceResponse_無効化_DTOに変換() {
            // Given
            NotificationTypePreferenceEntity entity = NotificationTypePreferenceEntity.builder()
                    .userId(1L)
                    .notificationType("CHAT_MESSAGE")
                    .isEnabled(false)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 21L);

            // When
            TypePreferenceResponse response = notificationMapper.toTypePreferenceResponse(entity);

            // Then
            assertThat(response.getIsEnabled()).isFalse();
            assertThat(response.getNotificationType()).isEqualTo("CHAT_MESSAGE");
        }

        @Test
        @DisplayName("正常系: 通知種別設定リストが変換される")
        void toTypePreferenceResponseList_正常_リスト変換() {
            // Given
            NotificationTypePreferenceEntity e1 = NotificationTypePreferenceEntity.builder()
                    .userId(1L).notificationType("SCHEDULE_REMINDER").isEnabled(true).build();
            NotificationTypePreferenceEntity e2 = NotificationTypePreferenceEntity.builder()
                    .userId(1L).notificationType("SYSTEM_ALERT").isEnabled(false).build();
            ReflectionTestUtils.setField(e1, "id", 1L);
            ReflectionTestUtils.setField(e2, "id", 2L);

            // When
            List<TypePreferenceResponse> responses = notificationMapper.toTypePreferenceResponseList(List.of(e1, e2));

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getNotificationType()).isEqualTo("SCHEDULE_REMINDER");
            assertThat(responses.get(1).getIsEnabled()).isFalse();
        }
    }
}
