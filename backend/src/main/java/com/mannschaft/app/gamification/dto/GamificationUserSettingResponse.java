package com.mannschaft.app.gamification.dto;

/**
 * ゲーミフィケーションユーザー設定レスポンスDTO。
 */
public record GamificationUserSettingResponse(
        boolean showInRanking,
        boolean showBadges
) {}
