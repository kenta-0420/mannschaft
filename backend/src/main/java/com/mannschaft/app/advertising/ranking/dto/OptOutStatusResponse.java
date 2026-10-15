package com.mannschaft.app.advertising.ranking.dto;

/**
 * opt-out操作結果レスポンスDTO。
 */
public record OptOutStatusResponse(long teamId, boolean optOut, String message) {}
