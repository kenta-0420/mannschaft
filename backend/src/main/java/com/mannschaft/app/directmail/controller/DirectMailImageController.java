package com.mannschaft.app.directmail.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.directmail.dto.DirectMailImageUploadResponse;
import com.mannschaft.app.directmail.service.DirectMailImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ダイレクトメール画像アップロードコントローラー。
 */
@RestController
@Tag(name = "DM画像アップロード", description = "F09.6 DM画像アップロード")
@RequiredArgsConstructor
public class DirectMailImageController {

    private final DirectMailImageService imageService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームDM用の画像をアップロードする。
     */
    @PostMapping(value = "/api/v1/teams/{teamId}/direct-mails/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "チームDM画像アップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "アップロード成功")
    public ResponseEntity<ApiResponse<DirectMailImageUploadResponse>> uploadTeamImage(
            @PathVariable Long teamId, @RequestParam("file") MultipartFile file) {
        DirectMailImageUploadResponse response = imageService.uploadImage("TEAM", teamId, getCurrentUserId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 組織DM用の画像をアップロードする。
     */
    @PostMapping(value = "/api/v1/organizations/{orgId}/direct-mails/images",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "組織DM画像アップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "アップロード成功")
    public ResponseEntity<ApiResponse<DirectMailImageUploadResponse>> uploadOrgImage(
            @PathVariable Long orgId, @RequestParam("file") MultipartFile file) {
        DirectMailImageUploadResponse response = imageService.uploadImage("ORGANIZATION", orgId, getCurrentUserId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
