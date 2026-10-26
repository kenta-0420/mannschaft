package com.mannschaft.app.chat.controller;

import com.mannschaft.app.chat.dto.AddBookmarkRequest;
import com.mannschaft.app.chat.dto.BookmarkResponse;
import com.mannschaft.app.chat.service.ChatBookmarkService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
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
 * チャットブックマークコントローラー。ブックマークの追加・一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/chat/bookmarks")
@Tag(name = "チャットブックマーク", description = "F04.2 メッセージブックマーク管理")
@RequiredArgsConstructor
public class ChatBookmarkController {

    private final ChatBookmarkService bookmarkService;


    /**
     * ブックマークを追加する。
     */
    @PostMapping
    @Operation(summary = "ブックマーク追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<BookmarkResponse>> addBookmark(
            @Valid @RequestBody AddBookmarkRequest request) {
        BookmarkResponse response = bookmarkService.addBookmark(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ブックマーク一覧を取得する。
     */
    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみで、リクエストは他ユーザーの識別子を受け取らない"
            + "（ChatBookmarkService#listBookmarks の findByUserIdOrderByCreatedAtDesc が認証主体に束縛される）")
    @GetMapping
    @Operation(summary = "ブックマーク一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<BookmarkResponse>>> listBookmarks() {
        List<BookmarkResponse> responses = bookmarkService.listBookmarks(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * ブックマークを削除する。
     *
     * <p>F04.2 Phase 11 第一陣: Service 実装済の {@code removeBookmark} を外部 API として公開する。
     * 削除対象は呼び出し元ユーザー自身のブックマークのみ（Repository 側で userId スコープ）。</p>
     */
    @SelfScopedEndpoint("削除対象は (SecurityUtils.getCurrentUserId(), messageId) で一意に解決され、他ユーザーの"
            + "ブックマーク行には到達しない"
            + "（ChatBookmarkService#removeBookmark の deleteByUserIdAndMessageId が認証主体に束縛される）")
    @DeleteMapping("/{messageId}")
    @Operation(summary = "ブックマーク削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> removeBookmark(@PathVariable Long messageId) {
        bookmarkService.removeBookmark(messageId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
