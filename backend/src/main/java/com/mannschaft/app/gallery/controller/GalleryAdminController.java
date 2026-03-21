package com.mannschaft.app.gallery.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.gallery.dto.RegenerateThumbnailsRequest;
import com.mannschaft.app.gallery.dto.RegenerateThumbnailsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ギャラリー管理者コントローラー。SYSTEM_ADMIN向けのサムネイル再生成APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/gallery")
@Tag(name = "ギャラリー管理", description = "F06.2 SYSTEM_ADMIN向けギャラリー管理API")
@RequiredArgsConstructor
public class GalleryAdminController {

    /**
     * サムネイルを一括再生成する（非同期ジョブ）。
     */
    @PostMapping("/regenerate-thumbnails")
    @Operation(summary = "サムネイル一括再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "ジョブ受付成功")
    public ResponseEntity<ApiResponse<RegenerateThumbnailsResponse>> regenerateThumbnails(
            @Valid @RequestBody(required = false) RegenerateThumbnailsRequest request) {
        // TODO: 非同期バッチジョブをディスパッチ
        String jobId = "regen-thumb-" + UUID.randomUUID().toString().substring(0, 8);
        RegenerateThumbnailsResponse response = new RegenerateThumbnailsResponse(jobId, "PROCESSING");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }
}
