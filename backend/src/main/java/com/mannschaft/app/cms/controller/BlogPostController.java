package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.service.BlogFeedService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ブログ記事コントローラー。記事のCRUD・公開制御・リビジョン管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/blog")
@Tag(name = "ブログ", description = "F06.1 ブログ記事CRUD・公開制御・リビジョン管理")
@RequiredArgsConstructor
public class BlogPostController {

    private final BlogPostService postService;
    private final BlogFeedService feedService;


    /**
     * 記事一覧を取得する。
     */
    @GetMapping("/posts")
    @Operation(summary = "記事一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<BlogPostResponse>> listPosts(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) String postType,
            @RequestParam(required = false) List<Long> tagIds,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // フィルタパラメータは BlogPostRepository のクエリ拡張時に対応予定
        Page<BlogPostResponse> result;
        if (teamId != null) {
            result = postService.listByTeam(teamId, PageRequest.of(page, size));
        } else {
            result = postService.listByOrganization(organizationId, PageRequest.of(page, size));
        }
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 記事詳細をslugで取得する。
     */
    @GetMapping("/posts/{slug}")
    @Operation(summary = "記事詳細（slug）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> getPostBySlug(
            @PathVariable String slug,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String previewToken) {
        BlogPostResponse response;
        if (previewToken != null) {
            response = postService.getBySlugWithPreviewToken(teamId, organizationId, userId, slug, previewToken);
        } else {
            response = postService.getBySlug(teamId, organizationId, userId, slug);
        }
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 記事を作成する。
     */
    @PostMapping("/posts")
    @Operation(summary = "記事作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> createPost(
            @Valid @RequestBody CreateBlogPostRequest request) {
        BlogPostResponse response = postService.createPost(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 記事を更新する。
     */
    @PutMapping("/posts/{id}")
    @Operation(summary = "記事更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBlogPostRequest request) {
        BlogPostResponse response = postService.updatePost(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 記事を削除する。
     */
    @DeleteMapping("/posts/{id}")
    @Operation(summary = "記事削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        postService.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 公開ステータスを変更する。
     */
    @PatchMapping("/posts/{id}/publish")
    @Operation(summary = "公開ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody PublishRequest request) {
        BlogPostResponse response = postService.changeStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 下書きを自動保存する（エディタ30秒間隔）。
     */
    @PatchMapping("/posts/{id}/auto-save")
    @Operation(summary = "下書き自動保存")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "保存成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> autoSave(
            @PathVariable Long id,
            @Valid @RequestBody AutoSaveRequest request) {
        BlogPostResponse response = postService.autoSave(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 一括ステータス変更を実行する（ARCHIVE / DELETE / PUBLISH）。
     */
    @PatchMapping("/posts/bulk")
    @Operation(summary = "一括ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "処理成功")
    public ResponseEntity<ApiResponse<BulkActionResponse>> bulkAction(
            @Valid @RequestBody BulkActionRequest request) {
        BulkActionResponse response = postService.bulkAction(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * RSS/Atomフィードを取得する（PUBLIC記事のみ）。
     */
    @GetMapping(value = "/feed", produces = {MediaType.APPLICATION_XML_VALUE, "application/rss+xml", "application/atom+xml"})
    @Operation(summary = "RSS/Atomフィード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<String> getFeed(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(defaultValue = "rss") String format) {
        List<BlogPostResponse> posts = postService.listPublicPostsForFeed(teamId, organizationId);
        String xml = feedService.generateFeedXml(posts, format, teamId, organizationId);

        String contentType = "atom".equalsIgnoreCase(format)
                ? "application/atom+xml; charset=UTF-8"
                : "application/rss+xml; charset=UTF-8";

        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Cache-Control", "public, max-age=600")
                .body(xml);
    }

    /**
     * 記事を複製する。
     */
    @PostMapping("/posts/{id}/duplicate")
    @Operation(summary = "記事複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> duplicatePost(@PathVariable Long id) {
        BlogPostResponse response = postService.duplicatePost(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * リビジョン一覧を取得する。
     */
    @GetMapping("/posts/{id}/revisions")
    @Operation(summary = "リビジョン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<com.mannschaft.app.cms.dto.RevisionResponse>>> listRevisions(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(postService.listRevisions(id)));
    }

    /**
     * リビジョンから復元する。
     */
    @PostMapping("/posts/{id}/revisions/{revisionId}/restore")
    @Operation(summary = "リビジョン復元")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復元成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> restoreRevision(
            @PathVariable Long id,
            @PathVariable Long revisionId) {
        BlogPostResponse response = postService.restoreRevision(id, revisionId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プレビュートークンを発行する。
     */
    @PostMapping("/posts/{id}/preview-token")
    @Operation(summary = "プレビュートークン発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> issuePreviewToken(@PathVariable Long id) {
        BlogPostResponse response = postService.issuePreviewToken(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * プレビュートークンを無効化する。
     */
    @DeleteMapping("/posts/{id}/preview-token")
    @Operation(summary = "プレビュートークン無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> revokePreviewToken(@PathVariable Long id) {
        postService.revokePreviewToken(id);
        return ResponseEntity.noContent().build();
    }
}
