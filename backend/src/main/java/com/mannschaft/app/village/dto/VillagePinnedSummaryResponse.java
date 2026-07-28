package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.UUID;

/**
 * ダッシュボード村フィード（F17.1 §4.13）の「ピン村」要約 DTO。
 *
 * <p>ユーザーがピン留めしている村のサイドバー表示用情報。
 * 未読件数は Phase 1 では 0 固定（既読管理連携は B11 以降の課題）。</p>
 *
 * @param id          村 ID
 * @param name        村名
 * @param iconUrl     村アイコンの表示用 URL（署名付き GET URL。未設定 / 解決失敗時は {@code null}。#2355）
 * @param unreadCount 未読件数（Phase 1 は 0 固定）
 */
@Builder
public record VillagePinnedSummaryResponse(
        UUID id,
        String name,
        String iconUrl,
        long unreadCount
) {}
