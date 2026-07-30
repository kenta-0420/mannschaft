package com.mannschaft.app.quickmemo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
 *
 * <p><b>認可の所在</b>: メモ ID を受け取る EP は
 * {@code @PreAuthorize("@quickMemoAccessGuard.canAccess(#id, authentication)")} で
 * 所有者一致を宣言的に強制する（{@code QuickMemoAccessGuard#canAccess}
 * が {@code findByIdAndUserId} で引き当てる）。</p>
 *
 * <p>一方、<b>メモ ID を受け取らない一覧・検索・作成の 4 EP は自己スコープ</b>であり、
 * 絞り込みキーが {@link SecurityUtils#getCurrentUserId()} に固定されていて
 * リクエストで指定できない。これらはガード呼び出しを持たないため認可番人
 * （{@code AuthzControllerGuardArchTest}）の呼び出しグラフ判定では認可シグナルとして
 * 検出されない。構造的に越境不能であることを監査のうえ
 * {@link AuthorizedInService} で明示承認し、回帰は
 * {@code QuickMemoSelfScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/quick-memos")
@Tag(name = "ポイっとメモ", description = "F02.5 ポイっとメモ管理")
@RequiredArgsConstructor
public class QuickMemoController {

    private final QuickMemoService quickMemoService;
    private final QuickMemoConvertToTodoService convertService;

    /**
     * 自分のメモ一覧を取得する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code QuickMemoService#listMemos}（QuickMemoService.java:49）の
     * 取得クエリは {@code findByUserIdAndStatusAndDeletedAtIsNull}
     * （QuickMemoService.java:51-52）で userId を必須条件に含む。
     * リクエストパラメータは status/page/size のみで対象ユーザーを指定できない。</p>
     */
    @GetMapping
    @Operation(summary = "メモ一覧取得")
    @AuthorizedInService
    public ResponseEntity<PagedResponse<QuickMemoResponse>> listMemos(
            @RequestParam(defaultValue = "UNSORTED") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(quickMemoService.listMemos(userId, status, page, size));
    }

    /**
     * メモを作成する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。作成されるメモの
     * {@code userId} は {@link SecurityUtils#getCurrentUserId()} に固定される
     * （QuickMemoService.java:84-85）。リクエストで指定できる {@code tagIds} は
     * {@code attachTags}（QuickMemoService.java:218）が
     * {@code findByIdAndScopeTypeAndScopeId(tagId, "PERSONAL", userId)}
     * （QuickMemoService.java:221）で本人所有の PERSONAL タグに限定するため、
     * 他ユーザーのタグや TEAM/ORGANIZATION タグを紐付けることはできない。</p>
     */
    @PostMapping
    @Operation(summary = "メモ作成")
    @AuthorizedInService
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

    /**
     * 自分のゴミ箱（論理削除済みメモ）一覧を取得する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code QuickMemoService#listTrash}（QuickMemoService.java:149）の
     * 取得クエリは {@code findByUserIdAndDeletedAtIsNotNull}
     * （QuickMemoService.java:150-151）で userId を必須条件に含む。</p>
     */
    @GetMapping("/trash")
    @Operation(summary = "ゴミ箱一覧")
    @AuthorizedInService
    public ResponseEntity<PagedResponse<QuickMemoResponse>> listTrash(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(quickMemoService.listTrash(userId, page, size));
    }

    /**
     * 自分のメモを全文検索する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code QuickMemoService#searchMemos}（QuickMemoService.java:66）の
     * {@code searchByKeyword}（QuickMemoService.java:69）は userId を必須条件に含むため、
     * 検索語で他ユーザーのメモ本文を掘り出すことはできない。
     * 検索語は {@code LikeEscapeUtils.contains} で LIKE メタ文字をエスケープする
     * （QuickMemoService.java:68）。</p>
     */
    @GetMapping("/search")
    @Operation(summary = "メモ検索")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<QuickMemoResponse>>> searchMemos(
            @RequestParam String q) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(quickMemoService.searchMemos(userId, q)));
    }
}
