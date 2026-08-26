package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.dto.GlobalCreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.service.BulletinReplyService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 * 掲示板返信「グローバル方式」コントローラー（F17.1 村掲示板グローバル方式 §3.12.1）。
 *
 * <p>パス変数方式（{@code /api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies}）とは別に、
 * スコープ情報を伴わず {@code threadId} / {@code replyId} のみで返信を操作する経路を提供する。
 * FE は村ページの詳細画面から本経路で返信を取得・投稿・編集・削除する
 * （{@code frontend/app/composables/bulletin/useBulletinReplies.ts}）。</p>
 *
 * <h2>FE 返信一覧の取得経路</h2>
 * <p>村掲示板の詳細画面は返信を <b>別 API（{@code GET /api/v1/bulletin/threads/{threadId}/replies}）</b>
 * で取得する設計とする（足軽B/C のスレッド詳細 GET は返信を同梱しないため）。本コントローラーの
 * {@code listReplies} がトップレベル返信をページングし、子返信を同梱して返す
 * （{@link BulletinReplyService#listRepliesGlobal}）。</p>
 *
 * <h2>スコープ分岐（サービス層で逆引き）</h2>
 * <ul>
 *   <li>{@code VILLAGE}: 閲覧は村可視性認可、投稿は村メンバー + 投稿主体検証、
 *       編集は本人のみ（ロック中はモデレーター）、削除は本人 or 村モデレーター。</li>
 *   <li>{@code ORGANIZATION / TEAM / PERSONAL}: 既存スコープ経路へ委譲。</li>
 * </ul>
 *
 * <p>添付ファイルの永続化基盤は bulletin ドメインに未整備のため、返信に添付があっても本フェーズでは
 * 保存しない（足軽C 申し送りを踏襲）。</p>
 */
@RestController
@RequestMapping("/api/v1/bulletin")
@Tag(name = "掲示板返信（グローバル）", description = "F17.1 村掲示板グローバル方式 返信一覧・CRUD")
@RequiredArgsConstructor
public class GlobalBulletinReplyController {

    private final BulletinReplyService replyService;

    /**
     * スレッドの返信一覧を取得する（グローバル方式）。トップレベル返信をページングし子返信を同梱する。
     *
     * @param threadId スレッド ID
     * @param page     ページ番号（0 始まり）
     * @param size     ページサイズ
     * @return 返信一覧（{@code { data: [...], meta: {...} }}）
     */
    @GetMapping("/threads/{threadId}/replies")
    @Operation(summary = "返信一覧（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ReplyResponse>> listReplies(
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReplyResponse> result = replyService.listRepliesGlobal(
                threadId, SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * スレッド直下に返信を作成する（グローバル方式）。
     *
     * @param threadId スレッド ID
     * @param request  作成リクエスト（{@code body}）
     * @return 作成された返信（{@code { data: {...} }}・201）
     */
    @PostMapping("/threads/{threadId}/replies")
    @Operation(summary = "返信作成（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReplyResponse>> createReply(
            @PathVariable Long threadId,
            @Valid @RequestBody GlobalCreateReplyRequest request) {
        ReplyResponse response = replyService.createReplyGlobal(
                threadId, null, SecurityUtils.getCurrentUserId(), request.getBody());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 既存返信へのネスト返信を作成する（グローバル方式）。親返信 ID は URL から解決する。
     *
     * <p>ネスト深さは最大5階層（depth 0〜4）。超過時は 400（{@code REPLY_DEPTH_EXCEEDED}）。</p>
     *
     * @param replyId 親返信 ID
     * @param request 作成リクエスト（{@code body}）
     * @return 作成された返信（{@code { data: {...} }}・201）
     */
    @PostMapping("/replies/{replyId}/replies")
    @Operation(summary = "ネスト返信作成（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ReplyResponse>> createNestedReply(
            @PathVariable Long replyId,
            @Valid @RequestBody GlobalCreateReplyRequest request) {
        ReplyResponse response = replyService.createNestedReplyGlobal(
                replyId, SecurityUtils.getCurrentUserId(), request.getBody());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 返信を更新する（グローバル方式）。投稿者本人のみ（VILLAGE はロック中モデレーターのみ）。
     *
     * @param replyId 返信 ID
     * @param request 更新リクエスト（{@code body}）
     * @return 更新された返信（{@code { data: {...} }}）
     */
    @PutMapping("/replies/{replyId}")
    @Operation(summary = "返信更新（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReplyResponse>> updateReply(
            @PathVariable Long replyId,
            @Valid @RequestBody UpdateReplyRequest request) {
        ReplyResponse response = replyService.updateReplyGlobal(
                replyId, SecurityUtils.getCurrentUserId(), request.getBody());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 返信を論理削除する（グローバル方式）。投稿者本人 or 村モデレーター（VILLAGE）／既存管理権限。
     *
     * @param replyId 返信 ID
     * @return 204 No Content
     */
    @DeleteMapping("/replies/{replyId}")
    @Operation(summary = "返信削除（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteReply(@PathVariable Long replyId) {
        replyService.deleteReplyGlobal(replyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
