package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.UserQuickMemoSettingsEntity;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * ユーザーごとのポイっとメモ設定レスポンス。
 */
public record UserQuickMemoSettingsResponse(
        Long userId,
        Boolean reminderEnabled,
        Integer defaultOffset1Days,
        LocalTime defaultTime1,
        Integer defaultOffset2Days,
        LocalTime defaultTime2,
        Integer defaultOffset3Days,
        LocalTime defaultTime3,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserQuickMemoSettingsResponse from(UserQuickMemoSettingsEntity entity) {
        return new UserQuickMemoSettingsResponse(
                entity.getUserId(),
                entity.getReminderEnabled(),
                entity.getDefaultOffset1Days(),
                entity.getDefaultTime1(),
                entity.getDefaultOffset2Days(),
                entity.getDefaultTime2(),
                entity.getDefaultOffset3Days(),
                entity.getDefaultTime3(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
