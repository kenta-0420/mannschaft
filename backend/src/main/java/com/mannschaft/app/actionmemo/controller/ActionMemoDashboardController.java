package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F02.5 Phase 4-β 管理職向け行動メモダッシュボードコントローラー。
 *
 * <p>チームの ADMIN または DEPUTY_ADMIN のみアクセス可能。
 * 対象メンバーが投稿した WORK カテゴリのメモ一覧を返す。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/members/{memberId}/action-memos")
@Tag(name = "行動メモ管理職ダッシュボード", description = "F02.5 Phase 4-β 管理職向けメモ閲覧 API")
@RequiredArgsConstructor
public class ActionMemoDashboardController {

    private final ActionMemoService actionMemoService;

    /**
     * チームメンバーの WORK メモ一覧を取得する（カーソルページネーション）。
     *
     * <p>認可: 呼び出し者が teamId の ADMIN または DEPUTY_ADMIN であること。
     * フィルタ: category=WORK AND postedTeamId=teamId AND userId=memberId。</p>
     *
     * @param teamId   チーム ID
     * @param memberId 対象メンバーのユーザー ID
     * @param cursor   前回最後のメモ ID（カーソル）
     * @param limit    取得件数（デフォルト 50、最大 200）
     */
    @GetMapping
    @Operation(summary = "チームメンバーの WORK メモ一覧取得（管理職向け）")
    public ResponseEntity<ActionMemoListResponse> listMemberMemos(
            @PathVariable Long teamId,
            @PathVariable Long memberId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Long cursorId = cursor != null ? parseCursor(cursor) : null;
        ActionMemoListResponse response = actionMemoService.listTeamMemberMemos(
                teamId, memberId, SecurityUtils.getCurrentUserId(), cursorId, limit != null ? limit : 50);
        return ResponseEntity.ok(response);
    }

    private Long parseCursor(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
