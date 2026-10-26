package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ダッシュボード関連エンティティの単体テスト。
 * エンティティのビジネスメソッドとライフサイクルを検証する。
 */
@DisplayName("ダッシュボードエンティティ 単体テスト")
class DashboardEntityTest {

    // ========================================
    // ChatContactFolderEntity
    // ========================================

    @Nested
    @DisplayName("ChatContactFolderEntity")
    class ChatContactFolderEntityTests {

        @Test
        @DisplayName("正常系: updateで名前・アイコン・色・並び順が更新される")
        void chatContactFolderEntity_update_フィールド更新() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("旧名前")
                    .icon("folder")
                    .color("#FF0000")
                    .sortOrder(1)
                    .build();

            // When
            folder.update("新名前", "star", "#00FF00", 3);

            // Then
            assertThat(folder.getName()).isEqualTo("新名前");
            assertThat(folder.getIcon()).isEqualTo("star");
            assertThat(folder.getColor()).isEqualTo("#00FF00");
            assertThat(folder.getSortOrder()).isEqualTo(3);
        }

        @Test
        @DisplayName("正常系: updateでiconがnullの場合は既存値を保持する")
        void chatContactFolderEntity_update_iconNull_既存値保持() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("フォルダ")
                    .icon("original-icon")
                    .color("#FF0000")
                    .sortOrder(1)
                    .build();

            // When
            folder.update("新名前", null, null, null);

            // Then
            assertThat(folder.getName()).isEqualTo("新名前");
            assertThat(folder.getIcon()).isEqualTo("original-icon");
            assertThat(folder.getColor()).isEqualTo("#FF0000");
            assertThat(folder.getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系: getMaxFoldersPerUserは20を返す")
        void chatContactFolderEntity_getMaxFoldersPerUser_20を返す() {
            // When
            int max = ChatContactFolderEntity.getMaxFoldersPerUser();

            // Then
            assertThat(max).isEqualTo(20);
        }
    }

    // ========================================
    // DashboardWidgetSettingEntity
    // ========================================

    @Nested
    @DisplayName("DashboardWidgetSettingEntity")
    class DashboardWidgetSettingEntityTests {

        @Test
        @DisplayName("正常系: changeVisibilityで表示状態が更新される")
        void dashboardWidgetSettingEntity_changeVisibility_表示更新() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .isVisible(true)
                    .sortOrder(1)
                    .build();

            // When
            entity.changeVisibility(false);

            // Then
            assertThat(entity.getIsVisible()).isFalse();
        }

        @Test
        @DisplayName("正常系: changeSortOrderで並び順が更新される")
        void dashboardWidgetSettingEntity_changeSortOrder_並び順更新() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .widgetKey("TEAM_NOTICES")
                    .isVisible(true)
                    .sortOrder(1)
                    .build();

            // When
            entity.changeSortOrder(5);

            // Then
            assertThat(entity.getSortOrder()).isEqualTo(5);
        }
    }

    // ========================================
    // ActivityFeedEntity
    // ========================================

    @Nested
    @DisplayName("ActivityFeedEntity")
    class ActivityFeedEntityTests {

        @Test
        @DisplayName("正常系: onCreateで作成日時が設定される")
        void activityFeedEntity_onCreate_作成日時設定() {
            // Given
            ActivityFeedEntity entity = ActivityFeedEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .actorId(1L)
                    .activityType(ActivityType.POST_CREATED)
                    .targetType(TargetType.TIMELINE_POST)
                    .targetId(100L)
                    .summary("新しい投稿を作成しました")
                    .build();

            // When
            ReflectionTestUtils.invokeMethod(entity, "onCreate");

            // Then
            assertThat(entity.getCreatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
        }
    }

    // ========================================
    // ChatContactFolderItemEntity
    // ========================================

    @Nested
    @DisplayName("ChatContactFolderItemEntity")
    class ChatContactFolderItemEntityTests {

        @Test
        @DisplayName("正常系: onCreateで作成日時が設定される")
        void chatContactFolderItemEntity_onCreate_作成日時設定() {
            // Given
            ChatContactFolderItemEntity entity = ChatContactFolderItemEntity.builder()
                    .folderId(1L)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(50L)
                    .build();

            // When
            ReflectionTestUtils.invokeMethod(entity, "onCreate");

            // Then
            assertThat(entity.getCreatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
            assertThat(entity.getFolderId()).isEqualTo(1L);
            assertThat(entity.getItemType()).isEqualTo(FolderItemType.DM_CHANNEL);
            assertThat(entity.getItemId()).isEqualTo(50L);
        }
    }

    // ========================================
    // DashboardWidgetSettingEntity - lifecycle
    // ========================================

    @Nested
    @DisplayName("DashboardWidgetSettingEntity lifecycle")
    class DashboardWidgetSettingEntityLifecycleTests {

        @Test
        @DisplayName("正常系: onCreateで作成・更新日時とデフォルト値が設定される")
        void dashboardWidgetSettingEntity_onCreate_ライフサイクル() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .build();

            // When
            ReflectionTestUtils.invokeMethod(entity, "onCreate");

            // Then
            assertThat(entity.getCreatedAt()).isNotNull();
            assertThat(entity.getUpdatedAt()).isNotNull();
            assertThat(entity.getIsVisible()).isTrue();
            assertThat(entity.getSortOrder()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: onUpdateで更新日時が設定される")
        void dashboardWidgetSettingEntity_onUpdate_更新日時設定() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .isVisible(true)
                    .sortOrder(1)
                    .build();
            ReflectionTestUtils.invokeMethod(entity, "onCreate");

            // When
            ReflectionTestUtils.invokeMethod(entity, "onUpdate");

            // Then
            assertThat(entity.getUpdatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
        }
    }

    // ========================================
    // ChatContactFolderEntity - lifecycle
    // ========================================

    @Nested
    @DisplayName("ChatContactFolderEntity lifecycle")
    class ChatContactFolderEntityLifecycleTests {

        @Test
        @DisplayName("正常系: onCreateで作成・更新日時とデフォルト値が設定される")
        void chatContactFolderEntity_onCreate_ライフサイクル() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("テストフォルダ")
                    .sortOrder(null)
                    .build();

            // When
            ReflectionTestUtils.invokeMethod(folder, "onCreate");

            // Then
            assertThat(folder.getCreatedAt()).isNotNull();
            assertThat(folder.getUpdatedAt()).isNotNull();
            assertThat(folder.getSortOrder()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: onUpdateで更新日時が設定される")
        void chatContactFolderEntity_onUpdate_更新日時設定() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("フォルダ")
                    .sortOrder(1)
                    .build();
            ReflectionTestUtils.invokeMethod(folder, "onCreate");

            // When
            ReflectionTestUtils.invokeMethod(folder, "onUpdate");

            // Then
            assertThat(folder.getUpdatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
        }
    }
}
