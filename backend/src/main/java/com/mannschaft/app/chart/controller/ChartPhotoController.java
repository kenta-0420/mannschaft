package com.mannschaft.app.chart.controller;

import com.mannschaft.app.chart.dto.ChartPhotoResponse;
import com.mannschaft.app.chart.service.ChartPhotoService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * カルテ写真コントローラー。写真のアップロード・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/charts")
@Tag(name = "カルテ写真", description = "F07.4 カルテ写真アップロード・削除")
@RequiredArgsConstructor
public class ChartPhotoController {

    private final ChartPhotoService chartPhotoService;

    /**
     * 6. 写真アップロード
     * POST /api/v1/teams/{teamId}/charts/{id}/photos
     */
    @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "写真アップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "アップロード成功")
    public ResponseEntity<ApiResponse<ChartPhotoResponse>> uploadPhoto(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam("photo_type") String photoType,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "is_shared_to_customer", required = false) Boolean isSharedToCustomer) {
        ChartPhotoResponse response = chartPhotoService.uploadPhoto(teamId, id, file, photoType, note, isSharedToCustomer);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 7. 写真削除
     * DELETE /api/v1/teams/{teamId}/charts/photos/{photoId}
     */
    @DeleteMapping("/photos/{photoId}")
    @Operation(summary = "写真削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePhoto(
            @PathVariable Long teamId,
            @PathVariable Long photoId) {
        chartPhotoService.deletePhoto(teamId, photoId);
        return ResponseEntity.noContent().build();
    }
}
