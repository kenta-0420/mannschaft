package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.dto.ActionMemoAuditLogResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.actionmemo.dto.AddTagsToMemoRequest;
import com.mannschaft.app.actionmemo.dto.AvailableTeamResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.LinkTodoRequest;
import com.mannschaft.app.actionmemo.dto.MoodStatsResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.actionmemo.service.ActionMemoTagService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/**
 * F02.5 行動メモコントローラー。
 *
 * <p>すべてのエンドポイントは認証ユーザー自身のデータのみを操作対象とする。
 * 所有者不一致・存在しない・論理削除済みは全て 404 を返す（IDOR 対策）。</p>
 *
 * <p><b>Phase 4 スコープ</b>: CRUD + link-todo + {@code publish-daily} + タグ追加/除去 + 気分集計。</p>
 */
@RestController
@RequestMapping("/api/v1/action-memos")
@Tag(name = "行動メモ", description = "F02.5 行動メモ CRUD")
@RequiredArgsConstructor
public class ActionMemoController {

    private final ActionMemoService actionMemoService;
    private final ActionMemoTagService actionMemoTagService;

    /**
     * 行動メモを1件作成する。
     */
    @PostMapping
    @Operation(summary = "行動メモ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ActionMemoResponse>> createMemo(
            @Valid @RequestBody CreateActionMemoRequest request) {
        ActionMemoResponse response = actionMemoService.createMemo(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自分の行動メモ一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "行動メモ一覧取得")
    public ResponseEntity<ActionMemoListResponse> listMemos(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "tag_id", required = false) Long tagId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        ActionMemoListResponse response = actionMemoService.listMemos(
                SecurityUtils.getCurrentUserId(), date, from, to, tagId, cursor, limit);
        return ResponseEntity.ok(response);
    }

    /**
     * 行動メモ1件を取得する。他人の id は 404。
     */
    @GetMapping("/{id}")
    @Operation(summary = "行動メモ詳細取得")
    public ResponseEntity<ApiResponse<ActionMemoResponse>> getMemo(@PathVariable Long id) {
        ActionMemoResponse response = actionMemoService.getMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 行動メモを更新する。他人の id は 404。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "行動メモ更新")
    public ResponseEntity<ApiResponse<ActionMemoResponse>> updateMemo(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActionMemoRequest request) {
        ActionMemoResponse response = actionMemoService.updateMemo(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 行動メモを論理削除する。他人の id は 404。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "行動メモ削除（論理削除）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMemo(@PathVariable Long id) {
        actionMemoService.deleteMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 行動メモに TODO を紐付ける。他人の TODO / PERSONAL 以外は 404。
     */
    @PostMapping("/{id}/link-todo")
    @Operation(summary = "行動メモに TODO を紐付け")
    public ResponseEntity<ApiResponse<ActionMemoResponse>> linkTodo(
            @PathVariable Long id,
            @Valid @RequestBody LinkTodoRequest request) {
        ActionMemoResponse response = actionMemoService.linkTodo(
                id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 当日分（または指定日分）のメモをまとめて PERSONAL タイムラインに投稿する。
     *
     * <p>設計書 §4 §5.4: 「今日を締める」儀式。0件の日は 400、冪等再実行は旧投稿を
     * 論理削除して差し替える。レートリミット 5 req/分（{@code ActionMemoRateLimitFilter}）。</p>
     */
    @PostMapping("/publish-daily")
    @Operation(summary = "行動メモ 当日分まとめ投稿（publish-daily）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishDailyResponse>> publishDaily(
            @Valid @RequestBody PublishDailyRequest request) {
        PublishDailyResponse response = actionMemoService.publishDaily(
                request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================================================================
    // メモへのタグ追加/除去（Phase 4）
    // ==================================================================

    /**
     * メモにタグを追加する（複数可）。1メモ10個上限。
     *
     * <p>設計書 §4: {@code POST /api/v1/action-memos/{id}/tags}。
     * URL パスが {@code /action-memos/{id}/tags} のため RESTful に本コントローラーに配置。</p>
     */
    @PostMapping("/{id}/tags")
    @Operation(summary = "メモにタグを追加")
    public ResponseEntity<Void> addTagsToMemo(
            @PathVariable Long id,
            @Valid @RequestBody AddTagsToMemoRequest request) {
        actionMemoTagService.addTagsToMemo(id, request.getTagIds(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * メモからタグを除去する。
     *
     * <p>設計書 §4: {@code DELETE /api/v1/action-memos/{id}/tags/{tagId}}。</p>
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    @Operation(summary = "メモからタグを除去")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "除去成功")
    public ResponseEntity<Void> removeTagFromMemo(
            @PathVariable Long id,
            @PathVariable Long tagId) {
        actionMemoTagService.removeTagFromMemo(id, tagId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ==================================================================
    // Phase 3: チームタイムライン投稿
    // ==================================================================

    /**
     * メモ1件をチームタイムラインに投稿する。
     *
     * <p>category = WORK のメモのみ可。既投稿メモは 409。
     * team_id 省略時は settings.default_post_team_id を使用。どちらも NULL なら 400。</p>
     */
    @PostMapping("/{id}/publish-to-team")
    @Operation(summary = "メモをチームタイムラインに投稿（個別即時投稿）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishToTeamResponse>> publishToTeam(
            @PathVariable Long id,
            @Valid @RequestBody PublishToTeamRequest request) {
        PublishToTeamResponse response = actionMemoService.publishToTeam(
                id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 当日の WORK メモをまとめてチームタイムラインに投稿する（日次まとめ投稿）。
     *
     * <p>postedTeamId が null のメモのみ対象（重複投稿防止）。0件は 400。</p>
     */
    @PostMapping("/publish-daily-to-team")
    @Operation(summary = "当日 WORK メモをチームタイムラインに一括投稿（日次まとめ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishDailyToTeamResponse>> publishDailyToTeam(
            @Valid @RequestBody PublishDailyToTeamRequest request) {
        PublishDailyToTeamResponse response = actionMemoService.publishDailyToTeam(
                request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 投稿先として選択可能なチーム一覧を取得する。
     *
     * <p>ユーザーが所属するチームの一覧を返す。
     * {@code is_default: true} はデフォルト投稿先として設定されているチーム。</p>
     */
    @GetMapping("/available-teams")
    @Operation(summary = "投稿先チーム一覧取得")
    public ResponseEntity<ApiResponse<List<AvailableTeamResponse>>> getAvailableTeams() {
        List<AvailableTeamResponse> response = actionMemoService.getAvailableTeams(
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================================================================
    // 気分集計（Phase 4）
    // ==================================================================

    /**
     * 期間内の気分（mood）分布を取得する。
     *
     * <p>設計書 §9 Phase 4「気分集計表示」。
     * {@code mood_enabled = true} のユーザーのみ意味があるが、
     * API 自体は全ユーザーに開放（0件なら {@code total: 0} で返す）。</p>
     */
    @GetMapping("/mood-stats")
    @Operation(summary = "気分集計取得")
    public ResponseEntity<ApiResponse<MoodStatsResponse>> getMoodStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MoodStatsResponse response = actionMemoService.getMoodStats(
                SecurityUtils.getCurrentUserId(), from, to);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Phase 5-1: メモに紐付く監査ログを取得する（折りたたみUI用）。
     *
     * <p>自分のメモのみ取得可能。最新10件を返す。
     * {@code ActionMemoAuditLogResponse} に変換して返すことで、
     * フロントエンドに不要なフィールド（IP アドレス・セッションハッシュ等）を露出しない。</p>
     */
    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "メモ監査ログ取得（Phase 5-1）")
    public ResponseEntity<ApiResponse<List<ActionMemoAuditLogResponse>>> getMemoAuditLogs(@PathVariable Long id) {
        List<AuditLogResponse> logs = actionMemoService.getMemoAuditLogs(
                id, SecurityUtils.getCurrentUserId());
        List<ActionMemoAuditLogResponse> response = logs.stream()
                .map(ActionMemoAuditLogResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Phase 4-β: チーム管理者が TODO 自動完了を差し戻す。
     *
     * <p>認可: 呼び出し者がメモの postedTeamId チームの ADMIN または DEPUTY_ADMIN であること。</p>
     */
    @DeleteMapping("/{id}/complete-todo")
    @Operation(summary = "TODO 差し戻し（Phase 4-β）— チーム管理者のみ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "差し戻し成功")
    public ResponseEntity<Void> revertTodoCompletion(@PathVariable Long id) {
        actionMemoService.revertTodoCompletion(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
