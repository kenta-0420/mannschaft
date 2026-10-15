package com.mannschaft.app.gamification.dto;

/**
 * ゲーミフィケーションユーザー設定更新リクエストDTO。
 */
public record UpdateGamificationUserSettingRequest(
        boolean showInRanking,
        boolean showBadges
) {}
