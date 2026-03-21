package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 掲示板スレッドコントローラー。スレッドのCRUD・検索・状態管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/threads")
@Tag(name = "掲示板スレッド", description = "F05.1 掲示板スレッドCRUD・検索・状態管理")
@RequiredArgsConstructor
public class BulletinThreadController {

    private final BulletinThreadService threadService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * スレッド一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "スレッド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ThreadResponse>> listThreads(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        Page<ThreadResponse> result;
        if (categoryId != null) {
            result = threadService.listThreadsByCategory(categoryId, PageRequest.of(page, size));
        } else {
            result = threadService.listThreads(type, scopeId, PageRequest.of(page, size));
        }
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * スレッド詳細を取得する。
     */
    @GetMapping("/{threadId}")
    @Operation(summary = "スレッド詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> getThread(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.getThread(type, scopeId, threadId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドを全文検索する。
     */
    @GetMapping("/search")
    @Operation(summary = "スレッド検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<PagedResponse<ThreadResponse>> searchThreads(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        Page<ThreadResponse> result = threadService.searchThreads(type, scopeId, keyword, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * スレッドを作成する。
     */
    @PostMapping
    @Operation(summary = "スレッド作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> createThread(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateThreadRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.createThread(type, scopeId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スレッドを更新する。
     */
    @PutMapping("/{threadId}")
    @Operation(summary = "スレッド更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> updateThread(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId,
            @Valid @RequestBody UpdateThreadRequest request) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.updateThread(type, scopeId, threadId, getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドを削除する。
     */
    @DeleteMapping("/{threadId}")
    @Operation(summary = "スレッド削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteThread(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        threadService.deleteThread(type, scopeId, threadId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ピン留めを切り替える。
     */
    @PostMapping("/{threadId}/pin")
    @Operation(summary = "ピン留め切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> togglePin(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.togglePin(type, scopeId, threadId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ロックを切り替える。
     */
    @PostMapping("/{threadId}/lock")
    @Operation(summary = "ロック切替")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "切替成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> toggleLock(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.toggleLock(type, scopeId, threadId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アーカイブする。
     */
    @PostMapping("/{threadId}/archive")
    @Operation(summary = "アーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> archive(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long threadId) {
        ScopeType type = ScopeType.valueOf(scopeType.toUpperCase());
        ThreadResponse response = threadService.archive(type, scopeId, threadId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
