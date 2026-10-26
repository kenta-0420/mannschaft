package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * F17.1 B4 — 村ニックネーム参照レスポンス。
 *
 * @param nickname              現在のニックネーム
 * @param avatarR2Key           R2 アバターキー（NULL 可）
 * @param bio                   自己紹介文（NULL 可）
 * @param lastChangedAt         最終変更日時
 * @param changeCountThisMonth  今月の変更回数（月初リセット）
 * @param monthlyLimit          月内変更上限（固定 3 回）
 */
@Builder
public record VillageNicknameResponse(
        String nickname,
        String avatarR2Key,
        String bio,
        LocalDateTime lastChangedAt,
        long changeCountThisMonth,
        int monthlyLimit
) {}
