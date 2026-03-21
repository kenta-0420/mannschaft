package com.mannschaft.app.member.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.member.dto.CreateTeamPageRequest;
import com.mannschaft.app.member.dto.PreviewTokenResponse;
import com.mannschaft.app.member.dto.PublishRequest;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.dto.UpdateTeamPageRequest;
import com.mannschaft.app.member.service.TeamPageService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * メンバー紹介ページコントローラー。ページのCRUD・公開管理・プレビュートークンAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/team/pages")
@Tag(name = "メンバー紹介ページ", description = "F06.2 メンバー紹介ページCRUD・公開管理")
@RequiredArgsConstructor
public class TeamPageController {

    private final TeamPageService pageService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * ページ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ページ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<TeamPageResponse>> listPages(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TeamPageResponse> result = pageService.listPages(teamId, organizationId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * ページ詳細を取得する（セクション・メンバー含む）。
     */
    @GetMapping("/{id}")
    @Operation(summary = "ページ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TeamPageResponse>> getPage(@PathVariable Long id) {
        TeamPageResponse response = pageService.getPage(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ページを作成する。
     */
    @PostMapping
    @Operation(summary = "ページ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TeamPageResponse>> createPage(
            @Valid @RequestBody CreateTeamPageRequest request) {
        TeamPageResponse response = pageService.createPage(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ページを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "ページ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TeamPageResponse>> updatePage(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTeamPageRequest request) {
        TeamPageResponse response = pageService.updatePage(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ページを削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "ページ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePage(@PathVariable Long id) {
        pageService.deletePage(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 公開ステータスを変更する。
     */
    @PatchMapping("/{id}/publish")
    @Operation(summary = "公開ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<TeamPageResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody PublishRequest request) {
        TeamPageResponse response = pageService.changeStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プレビュートークンを発行する。
     */
    @PostMapping("/{id}/preview-token")
    @Operation(summary = "プレビュートークン発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<PreviewTokenResponse>> issuePreviewToken(@PathVariable Long id) {
        PreviewTokenResponse response = pageService.issuePreviewToken(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プレビュートークンを無効化する。
     */
    @DeleteMapping("/{id}/preview-token")
    @Operation(summary = "プレビュートークン無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> revokePreviewToken(@PathVariable Long id) {
        pageService.revokePreviewToken(id);
        return ResponseEntity.noContent().build();
    }
}
