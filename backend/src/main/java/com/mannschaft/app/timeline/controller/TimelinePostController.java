package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.dto.UpdatePostRequest;
import com.mannschaft.app.timeline.service.TimelinePostService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムライン投稿コントローラー。投稿のCRUD・リプライ・ピン留めAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/posts")
@Tag(name = "タイムライン投稿", description = "F04.1 タイムライン投稿CRUD")
@RequiredArgsConstructor
public class TimelinePostController {

    private final TimelinePostService postService;


    /**
     * 投稿を作成する。
     */
    @PostMapping
    @Operation(summary = "投稿作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<PostResponse>> createPost(
            @Valid @RequestBody CreatePostRequest request) {
        PostResponse response = postService.createPost(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 投稿詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "投稿詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PostDetailResponse>> getPost(@PathVariable Long id) {
        PostDetailResponse response = postService.getPostDetail(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 投稿を更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "投稿更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PostResponse>> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request) {
        PostResponse response = postService.updatePost(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 投稿を削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "投稿削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deletePost(@PathVariable Long id) {
        postService.deletePost(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 投稿のリプライ一覧を取得する。
     */
    @GetMapping("/{id}/replies")
    @Operation(summary = "リプライ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PostResponse>>> getReplies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int size) {
        List<PostResponse> replies = postService.getReplies(id, size);
        return ResponseEntity.ok(ApiResponse.of(replies));
    }

    /**
     * 投稿のピン留め状態を切り替える。
     */
    @PostMapping("/{id}/pin")
    @Operation(summary = "ピン留め切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<PostResponse>> togglePin(
            @PathVariable Long id,
            @RequestParam boolean pinned) {
        PostResponse response = postService.togglePin(id, pinned, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
