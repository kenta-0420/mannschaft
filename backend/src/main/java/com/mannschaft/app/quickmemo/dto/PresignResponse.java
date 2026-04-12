package com.mannschaft.app.quickmemo.dto;

import java.time.LocalDateTime;

/**
 * Presigned URL 発行レスポンス。
 */
public record PresignResponse(String presignedUrl, String s3Key, LocalDateTime expiresAt) {}
