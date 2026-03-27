package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.DashboardMapper;
import com.mannschaft.app.dashboard.DashboardMapperImpl;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.TargetType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderItemResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DashboardMapper}（MapStruct実装）の単体テスト。
 * Entity → DTO の変換ロジックを検証する。
 */
@DisplayName("DashboardMapper 単体テスト")
class DashboardMapperTest {

    private DashboardMapper dashboardMapper;

    @BeforeEach
    void setUp() {
        dashboardMapper = new DashboardMapperImpl();
    }

    // ========================================
    // toWidgetSettingResponse
    // ========================================

    @Nested
    @DisplayName("toWidgetSettingResponse")
    class ToWidgetSettingResponse {

        @Test
        @DisplayName("正常系: エンティティがWidgetSettingResponseに変換される")
        void toWidgetSettingResponse_正常_DTOに変換() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .isVisible(true)
                    .sortOrder(1)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 10L);

            // When
            WidgetSettingResponse result = dashboardMapper.toWidgetSettingResponse(
                    entity, "通知", true, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getWidgetKey()).isEqualTo("NOTICES");
            assertThat(result.getName()).isEqualTo("通知");
            assertThat(result.isVisible()).isTrue();
            assertThat(result.getSortOrder()).isEqualTo(1);
            assertThat(result.isModuleEnabled()).isTrue();
            assertThat(result.getDisabledReason()).isNull();
        }

        @Test
        @DisplayName("正常系: モジュール無効の場合にdisabledReasonが設定される")
        void toWidgetSettingResponse_モジュール無効_disabledReason設定() {
            // Given
            DashboardWidgetSettingEntity entity = DashboardWidgetSettingEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .widgetKey("SHIFT")
                    .isVisible(false)
                    .sortOrder(5)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 20L);

            // When
            WidgetSettingResponse result = dashboardMapper.toWidgetSettingResponse(
                    entity, "シフト管理", false, "モジュールが無効です");

            // Then
            assertThat(result.isModuleEnabled()).isFalse();
            assertThat(result.getDisabledReason()).isEqualTo("モジュールが無効です");
        }
    }

    // ========================================
    // toDefaultWidgetSettingResponse
    // ========================================

    @Nested
    @DisplayName("toDefaultWidgetSettingResponse")
    class ToDefaultWidgetSettingResponse {

        @Test
        @DisplayName("正常系: WidgetKeyからデフォルトレスポンスが生成される")
        void toDefaultWidgetSettingResponse_正常_デフォルト値() {
            // Given
            WidgetKey widgetKey = WidgetKey.NOTICES;

            // When
            WidgetSettingResponse result = dashboardMapper.toDefaultWidgetSettingResponse(
                    widgetKey, "通知", true, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getWidgetKey()).isEqualTo("NOTICES");
            assertThat(result.getName()).isEqualTo("通知");
            assertThat(result.isVisible()).isEqualTo(widgetKey.isDefaultVisible());
            assertThat(result.getSortOrder()).isEqualTo(widgetKey.getDefaultSortOrder());
            assertThat(result.isModuleEnabled()).isTrue();
        }

        @Test
        @DisplayName("正常系: 複数のWidgetKeyでデフォルトレスポンスが生成される")
        void toDefaultWidgetSettingResponse_複数WidgetKey_各デフォルト値() {
            // When
            WidgetSettingResponse scheduleResult = dashboardMapper.toDefaultWidgetSettingResponse(
                    WidgetKey.UPCOMING_EVENTS, "スケジュール", true, null);
            WidgetSettingResponse activityResult = dashboardMapper.toDefaultWidgetSettingResponse(
                    WidgetKey.RECENT_ACTIVITY, "最近のアクティビティ", true, null);

            // Then
            assertThat(scheduleResult.getWidgetKey()).isEqualTo("UPCOMING_EVENTS");
            assertThat(activityResult.getWidgetKey()).isEqualTo("RECENT_ACTIVITY");
        }
    }

    // ========================================
    // toActivityFeedResponse
    // ========================================

    @Nested
    @DisplayName("toActivityFeedResponse")
    class ToActivityFeedResponse {

        @Test
        @DisplayName("正常系: ActivityFeedEntityがActivityFeedResponseに変換される")
        void toActivityFeedResponse_正常_DTOに変換() {
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
            ReflectionTestUtils.setField(entity, "id", 5L);
            ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.of(2026, 5, 1, 12, 0));

            ActivityFeedResponse.ActorSummary actor =
                    new ActivityFeedResponse.ActorSummary(1L, "yamada_taro", null);

            // When
            ActivityFeedResponse result = dashboardMapper.toActivityFeedResponse(entity, actor, "テストFC");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(5L);
            assertThat(result.getType()).isEqualTo("POST_CREATED");
            assertThat(result.getActor().getId()).isEqualTo(1L);
            assertThat(result.getActor().getDisplayName()).isEqualTo("yamada_taro");
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getScopeId()).isEqualTo(10L);
            assertThat(result.getScopeName()).isEqualTo("テストFC");
            assertThat(result.getTargetType()).isEqualTo("TIMELINE_POST");
            assertThat(result.getTargetId()).isEqualTo(100L);
            assertThat(result.getSummary()).isEqualTo("新しい投稿を作成しました");
            assertThat(result.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 1, 12, 0));
        }
    }

    // ========================================
    // toFolderItemResponse
    // ========================================

    @Nested
    @DisplayName("toFolderItemResponse")
    class ToFolderItemResponse {

        @Test
        @DisplayName("正常系: フォルダアイテムエンティティがDTOに変換される")
        void toFolderItemResponse_正常_DTOに変換() {
            // Given
            ChatContactFolderItemEntity entity = ChatContactFolderItemEntity.builder()
                    .folderId(1L)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(50L)
                    .build();
            ReflectionTestUtils.setField(entity, "id", 7L);

            // When
            ChatFolderItemResponse result = dashboardMapper.toFolderItemResponse(entity);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(7L);
            assertThat(result.getItemType()).isEqualTo("DM_CHANNEL");
            assertThat(result.getItemId()).isEqualTo(50L);
        }

        @Test
        @DisplayName("境界値: nullエンティティはnullを返す")
        void toFolderItemResponse_null_nullを返す() {
            // When
            ChatFolderItemResponse result = dashboardMapper.toFolderItemResponse(null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // toFolderItemResponseList
    // ========================================

    @Nested
    @DisplayName("toFolderItemResponseList")
    class ToFolderItemResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティがリストDTOに変換される")
        void toFolderItemResponseList_複数エンティティ_リストに変換() {
            // Given
            ChatContactFolderItemEntity item1 = ChatContactFolderItemEntity.builder()
                    .folderId(1L)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(10L)
                    .build();
            ReflectionTestUtils.setField(item1, "id", 1L);

            ChatContactFolderItemEntity item2 = ChatContactFolderItemEntity.builder()
                    .folderId(1L)
                    .itemType(FolderItemType.CONTACT)
                    .itemId(20L)
                    .build();
            ReflectionTestUtils.setField(item2, "id", 2L);

            // When
            List<ChatFolderItemResponse> result = dashboardMapper.toFolderItemResponseList(List.of(item1, item2));

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getItemType()).isEqualTo("DM_CHANNEL");
            assertThat(result.get(1).getItemType()).isEqualTo("CONTACT");
        }

        @Test
        @DisplayName("境界値: 空リストは空リストを返す")
        void toFolderItemResponseList_空リスト_空リストを返す() {
            // When
            List<ChatFolderItemResponse> result = dashboardMapper.toFolderItemResponseList(List.of());

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("境界値: nullリストはnullを返す")
        void toFolderItemResponseList_null_nullを返す() {
            // When
            List<ChatFolderItemResponse> result = dashboardMapper.toFolderItemResponseList(null);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // toFolderResponse
    // ========================================

    @Nested
    @DisplayName("toFolderResponse")
    class ToFolderResponse {

        @Test
        @DisplayName("正常系: フォルダエンティティとアイテムリストからレスポンスが生成される")
        void toFolderResponse_正常_レスポンス生成() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("お気に入り")
                    .icon("star")
                    .color("#FFD700")
                    .sortOrder(1)
                    .build();
            ReflectionTestUtils.setField(folder, "id", 3L);

            ChatContactFolderItemEntity item = ChatContactFolderItemEntity.builder()
                    .folderId(3L)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(100L)
                    .build();
            ReflectionTestUtils.setField(item, "id", 10L);

            // When
            ChatFolderResponse result = dashboardMapper.toFolderResponse(folder, List.of(item));

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(3L);
            assertThat(result.getName()).isEqualTo("お気に入り");
            assertThat(result.getIcon()).isEqualTo("star");
            assertThat(result.getColor()).isEqualTo("#FFD700");
            assertThat(result.getSortOrder()).isEqualTo(1);
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getItemType()).isEqualTo("DM_CHANNEL");
        }

        @Test
        @DisplayName("正常系: アイテムが空のフォルダも正常に変換される")
        void toFolderResponse_アイテム空_正常変換() {
            // Given
            ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                    .userId(1L)
                    .name("空フォルダ")
                    .sortOrder(2)
                    .build();
            ReflectionTestUtils.setField(folder, "id", 4L);

            // When
            ChatFolderResponse result = dashboardMapper.toFolderResponse(folder, List.of());

            // Then
            assertThat(result.getName()).isEqualTo("空フォルダ");
            assertThat(result.getItems()).isEmpty();
        }
    }
}
