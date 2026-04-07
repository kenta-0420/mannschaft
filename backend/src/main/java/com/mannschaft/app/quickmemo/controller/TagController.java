package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.dto.CreateTagRequest;
import com.mannschaft.app.quickmemo.dto.TagResponse;
import com.mannschaft.app.quickmemo.dto.UpdateTagRequest;
import com.mannschaft.app.quickmemo.service.TagService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 汎用タグ コントローラー。PERSONAL / TEAM / ORGANIZATION スコープのタグ CRUD を提供する。
 */
@RestController
@Tag(name = "タグ管理", description = "F02.5 汎用タグ（PERSONAL/TEAM/ORGANIZATION）")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    // ─── PERSONAL タグ ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/me/tags")
    @Operation(summary = "個人タグ一覧")
    public ResponseEntity<ApiResponse<PagedResponse<TagResponse>>> listPersonalTags(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(tagService.listTags("PERSONAL", userId, page, size)));
    }

    @PostMapping("/api/v1/me/tags")
    @Operation(summary = "個人タグ作成")
    public ResponseEntity<ApiResponse<TagResponse>> createPersonalTag(
            @Valid @RequestBody CreateTagRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(tagService.createTag("PERSONAL", userId, request)));
    }

    @PutMapping("/api/v1/me/tags/{tagId}")
    @Operation(summary = "個人タグ更新")
    public ResponseEntity<ApiResponse<TagResponse>> updatePersonalTag(
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.success(tagService.updateTag("PERSONAL", userId, tagId, request)));
    }

    @DeleteMapping("/api/v1/me/tags/{tagId}")
    @Operation(summary = "個人タグ削除（使用中は不可）")
    public ResponseEntity<Void> deletePersonalTag(@PathVariable Long tagId) {
        Long userId = SecurityUtils.getCurrentUserId();
        tagService.deleteTag("PERSONAL", userId, tagId);
        return ResponseEntity.noContent().build();
    }

    // ─── TEAM タグ ──────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/teams/{teamId}/tags")
    @Operation(summary = "チームタグ一覧")
    public ResponseEntity<ApiResponse<PagedResponse<TagResponse>>> listTeamTags(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(tagService.listTags("TEAM", teamId, page, size)));
    }

    @PostMapping("/api/v1/teams/{teamId}/tags")
    @Operation(summary = "チームタグ作成（ADMIN / MANAGE_TAG 権限必要）")
    public ResponseEntity<ApiResponse<TagResponse>> createTeamTag(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(tagService.createTag("TEAM", teamId, request)));
    }

    @PutMapping("/api/v1/teams/{teamId}/tags/{tagId}")
    @Operation(summary = "チームタグ更新")
    public ResponseEntity<ApiResponse<TagResponse>> updateTeamTag(
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(ApiResponse.success(tagService.updateTag("TEAM", teamId, tagId, request)));
    }

    @DeleteMapping("/api/v1/teams/{teamId}/tags/{tagId}")
    @Operation(summary = "チームタグ削除")
    public ResponseEntity<Void> deleteTeamTag(
            @PathVariable Long teamId,
            @PathVariable Long tagId) {
        tagService.deleteTag("TEAM", teamId, tagId);
        return ResponseEntity.noContent().build();
    }

    // ─── ORGANIZATION タグ ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/organizations/{orgId}/tags")
    @Operation(summary = "組織タグ一覧")
    public ResponseEntity<ApiResponse<PagedResponse<TagResponse>>> listOrgTags(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.success(tagService.listTags("ORGANIZATION", orgId, page, size)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tags")
    @Operation(summary = "組織タグ作成（ORGANIZATION_ADMIN 必要）")
    public ResponseEntity<ApiResponse<TagResponse>> createOrgTag(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(tagService.createTag("ORGANIZATION", orgId, request)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/tags/{tagId}")
    @Operation(summary = "組織タグ更新")
    public ResponseEntity<ApiResponse<TagResponse>> updateOrgTag(
            @PathVariable Long orgId,
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(ApiResponse.success(tagService.updateTag("ORGANIZATION", orgId, tagId, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/tags/{tagId}")
    @Operation(summary = "組織タグ削除")
    public ResponseEntity<Void> deleteOrgTag(
            @PathVariable Long orgId,
            @PathVariable Long tagId) {
        tagService.deleteTag("ORGANIZATION", orgId, tagId);
        return ResponseEntity.noContent().build();
    }
}
