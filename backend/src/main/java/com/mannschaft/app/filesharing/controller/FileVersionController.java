package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.CreateVersionRequest;
import com.mannschaft.app.filesharing.dto.FileVersionResponse;
import com.mannschaft.app.filesharing.service.SharedFileVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ファイルバージョンコントローラー。ファイルのバージョン管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files/{fileId}/versions")
@Tag(name = "ファイル共有 - バージョン", description = "F05.5 ファイルバージョン管理")
@RequiredArgsConstructor
public class FileVersionController {

    private final SharedFileVersionService versionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * ファイルの全バージョンを取得する。
     */
    @GetMapping
    @Operation(summary = "バージョン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FileVersionResponse>>> listVersions(
            @PathVariable Long fileId) {
        List<FileVersionResponse> response = versionService.listVersions(fileId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルの特定バージョンを取得する。
     */
    @GetMapping("/{versionNumber}")
    @Operation(summary = "バージョン詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FileVersionResponse>> getVersion(
            @PathVariable Long fileId,
            @PathVariable Integer versionNumber) {
        FileVersionResponse response = versionService.getVersion(fileId, versionNumber);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 新しいバージョンをアップロードする。
     */
    @PostMapping
    @Operation(summary = "新バージョンアップロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FileVersionResponse>> createVersion(
            @PathVariable Long fileId,
            @Valid @RequestBody CreateVersionRequest request) {
        FileVersionResponse response = versionService.createVersion(fileId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
