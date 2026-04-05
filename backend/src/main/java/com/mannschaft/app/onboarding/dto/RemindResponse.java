package com.mannschaft.app.onboarding.dto;

/**
 * リマインダー送信レスポンス。
 */
public record RemindResponse(
        Integer remindedCount,
        Integer totalInProgress
) {}
