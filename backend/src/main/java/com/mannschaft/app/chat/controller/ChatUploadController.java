package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.DownloadUrlResponse;
import com.mannschaft.app.chat.dto.UploadUrlRequest;
import com.mannschaft.app.chat.dto.UploadUrlResponse;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * チャットファイルアップロードコントローラー。Pre-signed URL発行APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat/files")
@Tag(name = "チャットファイル", description = "F04.2 チャットファイルアップロード")
@RequiredArgsConstructor
public class ChatUploadController {

    private static final long DEFAULT_EXPIRY_SECONDS = 3600L;

    /**
     * アップロード用 Pre-signed URL を発行する。
     */
    @PostMapping("/upload-url")
    @Operation(summary = "アップロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateUploadUrl(
            @Valid @RequestBody UploadUrlRequest request) {
        // TODO: S3 Pre-signed URL生成の実装
        String fileKey = "chat/" + UUID.randomUUID() + "/" + request.getFileName();
        UploadUrlResponse response = new UploadUrlResponse(
                "https://s3.example.com/presigned-upload/" + fileKey,
                fileKey,
                DEFAULT_EXPIRY_SECONDS
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ダウンロード用 Pre-signed URL を発行する。
     */
    @GetMapping("/{fileKey}/download-url")
    @Operation(summary = "ダウンロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<DownloadUrlResponse>> generateDownloadUrl(
            @PathVariable String fileKey) {
        // TODO: S3 Pre-signed URL生成の実装
        DownloadUrlResponse response = new DownloadUrlResponse(
                "https://s3.example.com/presigned-download/" + fileKey,
                DEFAULT_EXPIRY_SECONDS
        );
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
