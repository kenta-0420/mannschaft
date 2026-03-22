package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.BookmarkResponse;
import com.mannschaft.app.timeline.service.TimelineBookmarkService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムラインブックマークコントローラー。ブックマークの追加・削除・一覧取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/bookmarks")
@Tag(name = "タイムラインブックマーク", description = "F04.1 投稿ブックマーク管理")
@RequiredArgsConstructor
public class TimelineBookmarkController {

    private final TimelineBookmarkService bookmarkService;


    /**
     * 投稿をブックマークする。
     */
    @PostMapping("/{postId}")
    @Operation(summary = "ブックマーク追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<BookmarkResponse>> addBookmark(@PathVariable Long postId) {
        BookmarkResponse response = bookmarkService.addBookmark(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ブックマークを削除する。
     */
    @DeleteMapping("/{postId}")
    @Operation(summary = "ブックマーク削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeBookmark(@PathVariable Long postId) {
        bookmarkService.removeBookmark(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * ブックマーク一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ブックマーク一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BookmarkResponse>>> getBookmarks(
            @RequestParam(defaultValue = "20") int size) {
        List<BookmarkResponse> bookmarks = bookmarkService.getBookmarks(SecurityUtils.getCurrentUserId(), size);
        return ResponseEntity.ok(ApiResponse.of(bookmarks));
    }
}
