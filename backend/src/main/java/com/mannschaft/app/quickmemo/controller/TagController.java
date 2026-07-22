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
import org.springframework.security.access.prepost.PreAuthorize;
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
 *
 * <p><b>認可</b>（設計書 F02.5 §8.1 認可マトリクス）:</p>
 * <ul>
 *   <li>PERSONAL: {@code scopeId} をクライアントから受け取らず、常にサーバー側で
 *       {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserId()} を用いるため
 *       構造的に越境不能（C2 対応）</li>
 *   <li>TEAM 一覧 / ORGANIZATION 一覧: 当該スコープの所属メンバー以上
 *       （{@code @accessGuard.isScopeMember}）</li>
 *   <li>TEAM 作成・更新・削除 / ORGANIZATION 作成・更新・削除: 当該スコープの ADMIN / DEPUTY_ADMIN
 *       （{@code @accessGuard.isScopeAdmin}）</li>
 * </ul>
 *
 * <p>URL パスのスコープと DB 上の {@code tag.scope_type} / {@code tag.scope_id} の一致は
 * {@code TagService} の {@code findByIdAndScopeTypeAndScopeId} により担保されており、
 * 他スコープの {@code tagId} を指した越境は 404（TAG_NOT_FOUND）となる。</p>
 *
 * <p><b>注記</b>: {@code @Operation} summary が言及する {@code MANAGE_TAG} permission は
 * {@code permissions} テーブルにも Java 定数にも存在しないため、DEPUTY_ADMIN の許可判定は
 * permission ではなくロール（ADMIN / DEPUTY_ADMIN）で行う。これは同種の
 * {@code BlogTagController}（認可根治 Wave3-B7）と同一の運用。</p>
 */
@RestController
@Tag(name = "タグ管理", description = "F02.5 汎用タグ（PERSONAL/TEAM/ORGANIZATION）")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    // ─── PERSONAL タグ ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/me/tags")
    @Operation(summary = "個人タグ一覧")
    public ResponseEntity<PagedResponse<TagResponse>> listPersonalTags(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(tagService.listTags("PERSONAL", userId, page, size));
    }

    @PostMapping("/api/v1/me/tags")
    @Operation(summary = "個人タグ作成")
    public ResponseEntity<ApiResponse<TagResponse>> createPersonalTag(
            @Valid @RequestBody CreateTagRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(tagService.createTag("PERSONAL", userId, request)));
    }

    @PutMapping("/api/v1/me/tags/{tagId}")
    @Operation(summary = "個人タグ更新")
    public ResponseEntity<ApiResponse<TagResponse>> updatePersonalTag(
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(tagService.updateTag("PERSONAL", userId, tagId, request)));
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
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')")
    public ResponseEntity<PagedResponse<TagResponse>> listTeamTags(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(tagService.listTags("TEAM", teamId, page, size));
    }

    @PostMapping("/api/v1/teams/{teamId}/tags")
    @Operation(summary = "チームタグ作成（ADMIN / MANAGE_TAG 権限必要）")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<TagResponse>> createTeamTag(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(tagService.createTag("TEAM", teamId, request)));
    }

    @PutMapping("/api/v1/teams/{teamId}/tags/{tagId}")
    @Operation(summary = "チームタグ更新")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<TagResponse>> updateTeamTag(
            @PathVariable Long teamId,
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(ApiResponse.of(tagService.updateTag("TEAM", teamId, tagId, request)));
    }

    @DeleteMapping("/api/v1/teams/{teamId}/tags/{tagId}")
    @Operation(summary = "チームタグ削除")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<Void> deleteTeamTag(
            @PathVariable Long teamId,
            @PathVariable Long tagId) {
        tagService.deleteTag("TEAM", teamId, tagId);
        return ResponseEntity.noContent().build();
    }

    // ─── ORGANIZATION タグ ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/organizations/{orgId}/tags")
    @Operation(summary = "組織タグ一覧")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<PagedResponse<TagResponse>> listOrgTags(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(tagService.listTags("ORGANIZATION", orgId, page, size));
    }

    @PostMapping("/api/v1/organizations/{orgId}/tags")
    @Operation(summary = "組織タグ作成（ORGANIZATION_ADMIN 必要）")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<TagResponse>> createOrgTag(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(tagService.createTag("ORGANIZATION", orgId, request)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/tags/{tagId}")
    @Operation(summary = "組織タグ更新")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<TagResponse>> updateOrgTag(
            @PathVariable Long orgId,
            @PathVariable Long tagId,
            @Valid @RequestBody UpdateTagRequest request) {
        return ResponseEntity.ok(ApiResponse.of(tagService.updateTag("ORGANIZATION", orgId, tagId, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/tags/{tagId}")
    @Operation(summary = "組織タグ削除")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<Void> deleteOrgTag(
            @PathVariable Long orgId,
            @PathVariable Long tagId) {
        tagService.deleteTag("ORGANIZATION", orgId, tagId);
        return ResponseEntity.noContent().build();
    }
}
