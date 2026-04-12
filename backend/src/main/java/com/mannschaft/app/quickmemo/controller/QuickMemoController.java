package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoRequest;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoResponse;
import com.mannschaft.app.quickmemo.dto.CreateQuickMemoRequest;
import com.mannschaft.app.quickmemo.dto.QuickMemoResponse;
import com.mannschaft.app.quickmemo.dto.UpdateQuickMemoRequest;
import com.mannschaft.app.quickmemo.service.QuickMemoConvertToTodoService;
import com.mannschaft.app.quickmemo.service.QuickMemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ポイっとメモ コントローラー。
 */
@RestController
@RequestMapping("/api/v1/quick-memos")
@Tag(name = "ポイっとメモ", description = "F02.5 ポイっとメモ管理")
@RequiredArgsConstructor
public class QuickMemoController {

    private final QuickMemoService quickMemoService;
    private final QuickMemoConvertToTodoService convertService;

    @GetMapping
    @Operation(summary = "メモ一覧取得")
    public ResponseEntity<PagedResponse<QuickMemoResponse>> listMemos(
            @RequestParam(defaultValue = "UNSORTED") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(quickMemoService.listMemos(userId, status, page, size));
    }

    @PostMapping
    @Operation(summary = "メモ作成")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> createMemo(
            @Valid @RequestBody CreateQuickMemoRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        Long userId = SecurityUtils.getCurrentUserId();
        QuickMemoResponse response = quickMemoService.createMemo(userId, request, acceptLanguage);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "メモ詳細取得")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> getMemo(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.getMemoDetail(id, userId)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "メモ更新")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> updateMemo(
            @PathVariable Long id,
            @Valid @RequestBody UpdateQuickMemoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.updateMemo(id, userId, request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "メモ論理削除")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<Void> deleteMemo(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        quickMemoService.deleteMemo(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/archive")
    @Operation(summary = "メモをアーカイブ")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> archiveMemo(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.archiveMemo(id, userId)));
    }

    @PatchMapping("/{id}/restore")
    @Operation(summary = "アーカイブからメモを戻す")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> restoreMemo(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.restoreMemo(id, userId)));
    }

    @PostMapping("/{id}/convert-to-todo")
    @Operation(summary = "メモをTODOへ昇格")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<ConvertToTodoResponse>> convertToTodo(
            @PathVariable Long id,
            @Valid @RequestBody ConvertToTodoRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(convertService.convertToTodo(id, userId, request)));
    }

    @PostMapping("/{id}/undelete")
    @Operation(summary = "ゴミ箱から復元")
    @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")
    public ResponseEntity<ApiResponse<QuickMemoResponse>> undeleteMemo(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.undeleteMemo(id, userId)));
    }

    @GetMapping("/trash")
    @Operation(summary = "ゴミ箱一覧")
    public ResponseEntity<PagedResponse<QuickMemoResponse>> listTrash(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(quickMemoService.listTrash(userId, page, size));
    }

    @GetMapping("/search")
    @Operation(summary = "メモ検索")
    public ResponseEntity<ApiResponse<List<QuickMemoResponse>>> searchMemos(
            @RequestParam String q) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.searchMemos(userId, q)));
    }
}
