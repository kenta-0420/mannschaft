package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.service.SharedFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 共有ファイルコントローラー。ファイルのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "ファイル共有 - ファイル", description = "F05.5 ファイルCRUD")
@RequiredArgsConstructor
public class SharedFileController {

    private final SharedFileService fileService;


    /**
     * フォルダ内のファイル一覧をページングで取得する。
     */
    @GetMapping
    @Operation(summary = "ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FileResponse>> listFiles(
            @RequestParam Long folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileResponse> result = fileService.listFilesPaged(folderId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * ファイル詳細を取得する。
     */
    @GetMapping("/{fileId}")
    @Operation(summary = "ファイル詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FileResponse>> getFile(
            @PathVariable Long fileId) {
        FileResponse response = fileService.getFile(fileId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルを作成する。
     */
    @PostMapping
    @Operation(summary = "ファイル作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FileResponse>> createFile(
            @Valid @RequestBody CreateFileRequest request) {
        FileResponse response = fileService.createFile(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ファイルを更新する。
     */
    @PatchMapping("/{fileId}")
    @Operation(summary = "ファイル更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FileResponse>> updateFile(
            @PathVariable Long fileId,
            @Valid @RequestBody UpdateFileRequest request) {
        FileResponse response = fileService.updateFile(fileId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルを削除する。
     */
    @DeleteMapping("/{fileId}")
    @Operation(summary = "ファイル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long fileId) {
        fileService.deleteFile(fileId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
