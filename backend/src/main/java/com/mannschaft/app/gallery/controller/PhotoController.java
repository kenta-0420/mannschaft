package com.mannschaft.app.gallery.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.gallery.dto.DownloadResponse;
import com.mannschaft.app.gallery.dto.PhotoResponse;
import com.mannschaft.app.gallery.dto.UpdatePhotoRequest;
import com.mannschaft.app.gallery.service.PhotoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 写真コントローラー。個別写真の更新・削除・ダウンロードAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/gallery/photos")
@Tag(name = "写真", description = "F06.2 個別写真の更新・削除・ダウンロード")
@RequiredArgsConstructor
public class PhotoController {

    private final PhotoService photoService;

    /**
     * 写真情報を更新する（キャプション等）。
     */
    @PutMapping("/{id}")
    @Operation(summary = "写真更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PhotoResponse>> updatePhoto(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePhotoRequest request) {
        PhotoResponse response = photoService.updatePhoto(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 写真を削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "写真削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePhoto(@PathVariable Long id) {
        photoService.deletePhoto(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 個別写真ダウンロード（Pre-signed URL）。
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "写真ダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ダウンロードURL生成成功")
    public ResponseEntity<ApiResponse<DownloadResponse>> downloadPhoto(@PathVariable Long id) {
        DownloadResponse response = photoService.getPhotoDownloadUrl(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
