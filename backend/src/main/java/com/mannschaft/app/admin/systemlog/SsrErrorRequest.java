package com.mannschaft.app.admin.systemlog;

/**
 * Nuxt フロントエンドから送信される SSR エラーのリクエスト DTO。
 *
 * @param level     ログレベル（"error" | "warn"）
 * @param message   エラーメッセージ
 * @param stack     スタックトレース
 * @param path      エラーが発生したパス
 * @param timestamp タイムスタンプ（ISO-8601 形式）
 * @param userAgent ユーザーエージェント文字列
 */
public record SsrErrorRequest(
        String level,
        String message,
        String stack,
        String path,
        String timestamp,
        String userAgent
) {}
