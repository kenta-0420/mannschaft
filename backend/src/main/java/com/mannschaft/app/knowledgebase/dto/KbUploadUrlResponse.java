package com.mannschaft.app.knowledgebase.dto;

/**
 * ナレッジベース画像アップロードURL取得レスポンス。
 */
public record KbUploadUrlResponse(
        String uploadUrl,
        String s3Key,
        String imageUrl
) {}
