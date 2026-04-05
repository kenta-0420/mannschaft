package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderItemResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.FolderItemResponse;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * ダッシュボード機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface DashboardMapper {

    /**
     * ウィジェット設定エンティティをレスポンスに変換する。
     * モジュール有効/無効情報はService層で付与するため、ここでは基本フィールドのみ。
     */
    default WidgetSettingResponse toWidgetSettingResponse(
            DashboardWidgetSettingEntity entity,
            String name,
            boolean isModuleEnabled,
            String disabledReason) {
        return new WidgetSettingResponse(
                entity.getWidgetKey(),
                name,
                entity.getIsVisible(),
                entity.getSortOrder(),
                isModuleEnabled,
                disabledReason
        );
    }

    /**
     * WidgetKeyからデフォルト設定のレスポンスを生成する。
     */
    default WidgetSettingResponse toDefaultWidgetSettingResponse(
            WidgetKey widgetKey,
            String name,
            boolean isModuleEnabled,
            String disabledReason) {
        return new WidgetSettingResponse(
                widgetKey.name(),
                name,
                widgetKey.isDefaultVisible(),
                widgetKey.getDefaultSortOrder(),
                isModuleEnabled,
                disabledReason
        );
    }

    /**
     * アクティビティフィードエンティティをレスポンスに変換する。
     * actor, scopeName は Service 層で別途解決する。
     */
    default ActivityFeedResponse toActivityFeedResponse(
            ActivityFeedEntity entity,
            ActivityFeedResponse.ActorSummary actor,
            String scopeName) {
        return new ActivityFeedResponse(
                entity.getId(),
                entity.getActivityType().name(),
                actor,
                entity.getScopeType().name(),
                entity.getScopeId(),
                scopeName,
                entity.getTargetType().name(),
                entity.getTargetId(),
                entity.getSummary(),
                entity.getCreatedAt()
        );
    }

    @Mapping(target = "itemType", expression = "java(entity.getItemType().name())")
    ChatFolderItemResponse toFolderItemResponse(ChatContactFolderItemEntity entity);

    /**
     * フォルダアイテムエンティティを詳細レスポンス（属性付き）に変換する。
     */
    @Mapping(target = "itemType", expression = "java(entity.getItemType().name())")
    FolderItemResponse toFolderItemDetailResponse(ChatContactFolderItemEntity entity);

    List<ChatFolderItemResponse> toFolderItemResponseList(List<ChatContactFolderItemEntity> entities);

    /**
     * フォルダエンティティ + アイテム一覧をレスポンスに変換する。
     */
    default ChatFolderResponse toFolderResponse(
            ChatContactFolderEntity folder,
            List<ChatContactFolderItemEntity> items) {
        return new ChatFolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getIcon(),
                folder.getColor(),
                folder.getSortOrder(),
                toFolderItemResponseList(items)
        );
    }
}
