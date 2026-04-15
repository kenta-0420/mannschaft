package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.BlogMediaUploadUrlRequest;
import com.mannschaft.app.cms.dto.BlogMediaUploadUrlResponse;
import com.mannschaft.app.cms.service.BlogMediaService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ブログメディアコントローラー。
 * ブログ記事本文に埋め込む画像・動画のアップロード URL 発行 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/blog/media")
@Tag(name = "ブログメディア", description = "F06.1 ブログ記事本文埋め込み用メディアのアップロード URL 発行")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class BlogMediaController {

    private final BlogMediaService blogMediaService;

    /**
     * ブログ本文埋め込み用メディア（画像・動画）のアップロード URL を発行する。
     * IMAGE の場合は Presigned PUT URL を返す。
     * VIDEO の場合は Multipart Upload の uploadId を返す。
     *
     * @param request リクエスト情報（mediaType, contentType, fileSize, scopeType, scopeId, blogPostId）
     * @return アップロード URL 発行レスポンス
     */
    @PostMapping("/upload-url")
    @Operation(
            summary = "メディアアップロード URL 発行",
            description = "IMAGE → Presigned PUT URL 発行。VIDEO → Multipart Upload 開始。"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL 発行成功")
    public ResponseEntity<ApiResponse<BlogMediaUploadUrlResponse>> generateUploadUrl(
            @RequestBody @Valid BlogMediaUploadUrlRequest request) {

        Long uploaderId = SecurityUtils.getCurrentUserId();
        BlogMediaUploadUrlResponse response = blogMediaService.generateUploadUrl(uploaderId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
