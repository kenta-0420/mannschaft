package com.mannschaft.app.cms.controller;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogReactionResponse;
import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.dto.UpdateBlogSettingsRequest;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogReactionService;
import com.mannschaft.app.cms.service.UserBlogSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 個人ブログコントローラー。個人ブログ記事CRUD・共有・セルフレビュー設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "個人ブログ", description = "F06.1 個人ブログ記事CRUD・共有・セルフレビュー設定")
@RequiredArgsConstructor
public class PersonalBlogController {

    private final BlogPostService postService;
    private final UserBlogSettingsService settingsService;
    private final BlogReactionService reactionService;


    /**
     * 個人ブログ記事一覧を取得する。
     */
    @GetMapping("/{userId}/blog/posts")
    @Operation(summary = "個人ブログ記事一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<BlogPostResponse>> listUserPosts(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BlogPostResponse> result = postService.listByUser(userId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 個人ブログ記事詳細を取得する。
     */
    @GetMapping("/{userId}/blog/posts/{slug}")
    @Operation(summary = "個人ブログ記事詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> getUserPost(
            @PathVariable Long userId,
            @PathVariable String slug) {
        BlogPostResponse response = postService.getBySlug(null, null, userId, slug);
        // リアクション情報（みたよ！）を付与する
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        BlogReactionResponse reactionStatus = reactionService.getReactionStatus(response.getId(), currentUserId);
        response = response.withReaction(reactionStatus.isMitayo(), reactionStatus.getMitayoCount());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 認証ユーザー自身のブログ記事一覧を取得する。
     */
    @GetMapping("/me/blog/posts")
    @Operation(summary = "自分のブログ記事一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<BlogPostResponse>> listMyPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Page<BlogPostResponse> result = postService.listByUser(userId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 個人ブログ記事を作成する。
     */
    @PostMapping("/me/blog/posts")
    @Operation(summary = "個人ブログ記事作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> createPost(
            @Valid @RequestBody CreateBlogPostRequest request) {
        BlogPostResponse response = postService.createPost(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 個人ブログ記事を更新する。
     */
    @PutMapping("/me/blog/posts/{id}")
    @Operation(summary = "個人ブログ記事更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody com.mannschaft.app.cms.dto.UpdateBlogPostRequest request) {
        BlogPostResponse response = postService.updatePost(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人ブログ記事を削除する。
     */
    @DeleteMapping("/me/blog/posts/{id}")
    @Operation(summary = "個人ブログ記事削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        postService.deletePost(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 個人ブログ公開ステータスを変更する。
     */
    @PatchMapping("/me/blog/posts/{id}/publish")
    @Operation(summary = "個人ブログ公開ステータス変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody PublishRequest request) {
        BlogPostResponse response = postService.changeStatus(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 個人ブログ記事をチーム/組織に共有する（実名記事のみ）。
     */
    @PostMapping("/me/blog/posts/{id}/share")
    @Operation(summary = "個人ブログ記事共有")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "共有成功")
    public ResponseEntity<ApiResponse<SharePostResponse>> sharePost(
            @PathVariable Long id,
            @Valid @RequestBody SharePostRequest request) {
        SharePostResponse response = postService.sharePost(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 共有を取り消す。
     */
    @DeleteMapping("/me/blog/posts/{id}/shares/{shareId}")
    @Operation(summary = "共有取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    public ResponseEntity<Void> revokeShare(
            @PathVariable Long id,
            @PathVariable Long shareId) {
        postService.revokeShare(id, shareId);
        return ResponseEntity.noContent().build();
    }

    /**
     * セルフレビュー結果を処理する（PUBLISH / DRAFT / DELETE）。
     */
    @PatchMapping("/me/blog/posts/{id}/self-review")
    @Operation(summary = "セルフレビュー結果")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "処理成功")
    public ResponseEntity<ApiResponse<BlogPostResponse>> selfReview(
            @PathVariable Long id,
            @Valid @RequestBody SelfReviewRequest request) {
        BlogPostResponse response = postService.selfReview(id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * セルフレビュー設定を取得する。
     */
    @GetMapping("/me/blog/settings")
    @Operation(summary = "セルフレビュー設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<BlogSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.of(settingsService.getOrCreateSettings(SecurityUtils.getCurrentUserId())));
    }

    /**
     * セルフレビュー設定を更新する。
     */
    @PutMapping("/me/blog/settings")
    @Operation(summary = "セルフレビュー設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<BlogSettingsResponse>> updateSettings(
            @Valid @RequestBody UpdateBlogSettingsRequest request) {
        return ResponseEntity.ok(ApiResponse.of(settingsService.updateSettings(SecurityUtils.getCurrentUserId(), request)));
    }
}
