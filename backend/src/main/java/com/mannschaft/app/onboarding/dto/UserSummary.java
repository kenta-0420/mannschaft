package com.mannschaft.app.onboarding.dto;

/**
 * ユーザーサマリー（内部DTO）。進捗一覧等で使用。
 */
public record UserSummary(
        Long id,
        String displayName,
        String avatarUrl
) {}
