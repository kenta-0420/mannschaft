package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.filesharing.dto.StarResponse;
import com.mannschaft.app.filesharing.service.SharedFileStarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ファイルスターコントローラー。ファイルのお気に入り管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files/{fileId}/stars")
@Tag(name = "ファイル共有 - スター", description = "F05.5 ファイルスター管理")
@RequiredArgsConstructor
public class FileStarController {

    private final SharedFileStarService starService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * スターを追加する。
     */
    @PostMapping
    @Operation(summary = "スター追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<StarResponse>> addStar(
            @PathVariable Long fileId) {
        StarResponse response = starService.addStar(fileId, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スターを削除する。
     */
    @DeleteMapping
    @Operation(summary = "スター削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeStar(
            @PathVariable Long fileId) {
        starService.removeStar(fileId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * ユーザーのスター一覧を取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "自分のスター一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<StarResponse>>> listMyStars(
            @PathVariable Long fileId) {
        List<StarResponse> response = starService.listStarsByUser(getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
