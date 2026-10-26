package com.mannschaft.app.budget.dto;

/**
 * ユーザー概要DTO。取引の作成者・承認者表示用。
 */
public record UserSummary(
        Long id,
        String displayName
) {
}
