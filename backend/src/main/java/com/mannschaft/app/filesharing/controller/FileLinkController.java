package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ファイル共有リンクコントローラー。外部共有リンク管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "ファイル共有 - 共有リンク", description = "F05.5 ファイル共有リンク管理")
@RequiredArgsConstructor
public class FileLinkController {

    private final SharedFileLinkService linkService;


    /**
     * ファイルの共有リンク一覧を取得する。
     */
    @GetMapping("/files/{fileId}/links")
    @Operation(summary = "共有リンク一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<LinkResponse>>> listLinks(
            @PathVariable Long fileId) {
        List<LinkResponse> response = linkService.listLinks(fileId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 共有リンクを作成する。
     */
    @PostMapping("/files/{fileId}/links")
    @Operation(summary = "共有リンク作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<LinkResponse>> createLink(
            @PathVariable Long fileId,
            @Valid @RequestBody CreateLinkRequest request) {
        LinkResponse response = linkService.createLink(fileId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 共有リンクを削除する。
     */
    @DeleteMapping("/files/{fileId}/links/{linkId}")
    @Operation(summary = "共有リンク削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteLink(
            @PathVariable Long fileId,
            @PathVariable Long linkId) {
        linkService.deleteLink(linkId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 共有リンクでファイルにアクセスする。
     */
    @PostMapping("/shared-links/{token}/access")
    @Operation(summary = "共有リンクアクセス")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アクセス成功")
    public ResponseEntity<ApiResponse<FileResponse>> accessLink(
            @PathVariable String token,
            @RequestBody(required = false) AccessLinkRequest request) {
        FileResponse response = linkService.accessLink(token, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
