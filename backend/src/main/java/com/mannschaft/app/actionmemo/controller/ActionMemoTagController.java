package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.dto.ActionMemoTagResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.service.ActionMemoTagService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F02.5 行動メモタグコントローラー（Phase 4）。
 *
 * <p>設計書 §4 のタグ CRUD エンドポイントを提供する。
 * すべてのエンドポイントは認証ユーザー自身のタグのみを操作対象とする。
 * 所有者不一致・存在しない・論理削除済みは全て 404 を返す（IDOR 対策）。</p>
 *
 * <p>レートリミット: {@code POST /api/v1/action-memo-tags} は 20 req/分
 * （{@code ActionMemoRateLimitFilter} で設定済み）。</p>
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: タグの所有者は
 * {@link SecurityUtils#getCurrentUserId()} に固定され、リクエストで指定できない。
 * ID を伴う更新・削除は {@code ActionMemoTagService#findOwnTagOrThrow}
 * （ActionMemoTagService.java:250）が {@code (tagId, userId)} の複合条件で引き当てるため、
 * 他ユーザーのタグは不存在と区別されず 404 で秘匿される。
 * 認可番人の白名簿クラス経由ではないため監査済マーカーで明示承認する。
 * 回帰は {@code ActionMemoScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/action-memo-tags")
@Tag(name = "行動メモタグ", description = "F02.5 行動メモタグ CRUD")
@RequiredArgsConstructor
public class ActionMemoTagController {

    private final ActionMemoTagService tagService;

    /**
     * 自分のタグ一覧を取得する（サジェスト候補用。論理削除済みは含まない）。
     */
    @GetMapping
    @Operation(summary = "タグ一覧取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<ActionMemoTagResponse>>> listTags() {
        List<ActionMemoTagResponse> tags = tagService.getTags(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(tags));
    }

    /**
     * タグを作成する。1ユーザー100件上限。
     * レートリミット: 20 req/分（ActionMemoRateLimitFilter）。
     */
    @PostMapping
    @Operation(summary = "タグ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoTagResponse>> createTag(
            @Valid @RequestBody CreateActionMemoTagRequest request) {
        ActionMemoTagResponse response = tagService.createTag(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * タグを更新する（名前・色）。他人のタグは 404。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "タグ更新")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoTagResponse>> updateTag(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActionMemoTagRequest request) {
        ActionMemoTagResponse response = tagService.updateTag(
                id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * タグを論理削除する。復活機能なし（§11 #9）。他人のタグは 404。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "タグ削除（論理削除）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @AuthorizedInService
    public ResponseEntity<Void> deleteTag(@PathVariable Long id) {
        tagService.deleteTag(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
