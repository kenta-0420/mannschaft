package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.dto.ActionMemoAuditLogResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.actionmemo.dto.AddTagsToMemoRequest;
import com.mannschaft.app.actionmemo.dto.AvailableOrgResponse;
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
import com.mannschaft.app.actionmemo.service.ActionMemoAnalyticsService;
import com.mannschaft.app.actionmemo.service.ActionMemoAdminService;
import com.mannschaft.app.actionmemo.service.ActionMemoPublishingService;
import com.mannschaft.app.actionmemo.service.ActionMemoScopeService;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.actionmemo.service.ActionMemoTagService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
 *
 * <p><b>認可の所在（{@link AuthorizedInService} を各 EP に付与している理由）</b>:
 * 本コントローラーは scopeId をリクエストから受け取らず、常に
 * {@link SecurityUtils#getCurrentUserId()} を Service へ渡す。Service 側は
 * {@code memoRepository.findByIdAndUserId} 等の複合条件で対象 entity を引き当てることにより
 * 所有者一致を強制し、不一致・不存在・論理削除済みを区別せず 404 で秘匿する（BOLA 対策）。
 * この方式は認可番人（{@code AuthzControllerGuardArchTest}）の白名簿クラス
 * （{@code AccessControlService} / {@code *AccessGuard} 等）への呼び出しではないため
 * 呼び出しグラフ判定では認可シグナルとして検出されない。よって監査済マーカーで明示承認し、
 * 各 EP の Javadoc に認可の実施箇所を {@code ファイル:行} で記載する。
 * 回帰は {@code ActionMemoScopeContractIT}（他ユーザー→404／自己スコープ隔離）で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/action-memos")
@Tag(name = "行動メモ", description = "F02.5 行動メモ CRUD")
@RequiredArgsConstructor
public class ActionMemoController {

    private final ActionMemoService actionMemoService;
    private final ActionMemoPublishingService actionMemoPublishingService;
    private final ActionMemoScopeService actionMemoScopeService;
    private final ActionMemoAnalyticsService actionMemoAnalyticsService;
    private final ActionMemoAdminService actionMemoAdminService;
    private final ActionMemoTagService actionMemoTagService;

    /**
     * 行動メモを1件作成する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。作成者は
     * {@link SecurityUtils#getCurrentUserId()} に固定され、リクエストで指定できない。
     * 付随して指定される他ドメイン ID はすべて所属・所有を検証する:
     * {@code tag_ids} は {@code ActionMemoService#validateAndFetchTags}
     * （ActionMemoService.java:619）、{@code related_todo_id} は
     * {@code ActionMemoService#validateTodoScope}（ActionMemoService.java:534）、
     * {@code organization_id} は所属検証（ActionMemoService.java:149）。</p>
     */
    @PostMapping
    @Operation(summary = "行動メモ作成")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ActionMemoResponse>> createMemo(
            @Valid @RequestBody CreateActionMemoRequest request) {
        ActionMemoResponse response = actionMemoService.createMemo(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 自分の行動メモ一覧を取得する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。一覧の絞り込みキーは
     * {@link SecurityUtils#getCurrentUserId()} に固定され、リクエストで指定できない。
     * 全 3 経路のクエリが userId を必須条件に含む（ActionMemoService.java:269-273）ため、
     * 他ユーザーのメモは結果に混入しない。{@code tag_id} フィルタも自分のメモ集合に対してのみ適用される。</p>
     */
    @GetMapping
    @Operation(summary = "行動メモ一覧取得")
    @AuthorizedInService
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモの参照は作成者本人に限定する。
     * {@code ActionMemoService#getMemo}（ActionMemoService.java:225）が
     * {@code findOwnMemoOrThrow}（ActionMemoService.java:508）で
     * {@code (id, userId)} の複合条件により引き当てるため、他ユーザーのメモは
     * 不存在と区別されず 404 で秘匿される。</p>
     */
    @GetMapping("/{id}")
    @Operation(summary = "行動メモ詳細取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoResponse>> getMemo(@PathVariable Long id) {
        ActionMemoResponse response = actionMemoService.getMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 行動メモを更新する。他人の id は 404。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモの更新は作成者本人に限定する。
     * {@code ActionMemoService#updateMemo}（ActionMemoService.java:317）が冒頭で
     * {@code findOwnMemoOrThrow}（ActionMemoService.java:508）を通す。
     * 差し替えるタグ・関連 TODO・組織もそれぞれ所有／所属を再検証する
     * （ActionMemoService.java:374 / :343 / :393）。</p>
     */
    @PatchMapping("/{id}")
    @Operation(summary = "行動メモ更新")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<ActionMemoResponse>> updateMemo(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActionMemoRequest request) {
        ActionMemoResponse response = actionMemoService.updateMemo(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 行動メモを論理削除する。他人の id は 404。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモの削除は作成者本人に限定する。
     * {@code ActionMemoService#deleteMemo}（ActionMemoService.java:458）が冒頭で
     * {@code findOwnMemoOrThrow}（ActionMemoService.java:508）を通す。</p>
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "行動メモ削除（論理削除）")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMemo(@PathVariable Long id) {
        actionMemoService.deleteMemo(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 行動メモに TODO を紐付ける。他人の TODO / PERSONAL 以外は 404。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモ側とTODO側の二段で認可する。
     * {@code ActionMemoService#linkTodo}（ActionMemoService.java:487）が
     * {@code findOwnMemoOrThrow}（ActionMemoService.java:508）でメモの所有者一致を要求し、
     * 続く {@code validateTodoScope}（ActionMemoService.java:534）が TODO 側のスコープを
     * entity 由来で検証する（PERSONAL は本人所有のみ・TEAM は所属チームのみ）。
     * いずれの違反も 404 で秘匿する。</p>
     */
    @PostMapping("/{id}/link-todo")
    @Operation(summary = "行動メモに TODO を紐付け")
    @AuthorizedInService
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。投稿対象は
     * {@code memoRepository.findByUserIdAndMemoDate}（ActionMemoPublishingService.java:102）で
     * 認証主体のメモのみに限定され、生成される PERSONAL タイムライン投稿の
     * {@code scopeId}/{@code userId} も認証主体に固定される
     * （ActionMemoPublishingService.java:129-134）。メモ ID をリクエストで指定する余地がない。</p>
     */
    @PostMapping("/publish-daily")
    @Operation(summary = "行動メモ 当日分まとめ投稿（publish-daily）")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishDailyResponse>> publishDaily(
            @Valid @RequestBody PublishDailyRequest request) {
        PublishDailyResponse response = actionMemoPublishingService.publishDaily(
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモ側とタグ側の二段で認可する。
     * {@code ActionMemoTagService#addTagsToMemo}（ActionMemoTagService.java:181）が
     * メモの所有者一致（ActionMemoTagService.java:183）と
     * 全タグの所有者一致（ActionMemoTagService.java:188）の両方を要求するため、
     * 他ユーザーのメモにタグを付けることも、他ユーザーのタグを自分のメモに付けることもできない。</p>
     */
    @PostMapping("/{id}/tags")
    @Operation(summary = "メモにタグを追加")
    @AuthorizedInService
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモからのタグ除去は作成者本人に限定する。
     * {@code ActionMemoTagService#removeTagFromMemo}（ActionMemoTagService.java:230）が冒頭で
     * メモの所有者一致（ActionMemoTagService.java:232）を要求し、
     * 中間レコードも {@code (memoId, tagId)} の複合条件で引き当てる
     * （ActionMemoTagService.java:236）。</p>
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    @Operation(summary = "メモからタグを除去")
    @AuthorizedInService
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモ側と投稿先チーム側の二段で認可する。
     * {@code ActionMemoPublishingService#publishToTeam}（ActionMemoPublishingService.java:183）が
     * メモの所有者一致（ActionMemoPublishingService.java:185）を要求し、
     * リクエストで指定された {@code team_id} は所属チームであることを検証する
     * （ActionMemoPublishingService.java:202）。非所属チームへの投稿は 404 で秘匿する。</p>
     */
    @PostMapping("/{id}/publish-to-team")
    @Operation(summary = "メモをチームタイムラインに投稿（個別即時投稿）")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishToTeamResponse>> publishToTeam(
            @PathVariable Long id,
            @Valid @RequestBody PublishToTeamRequest request) {
        PublishToTeamResponse response = actionMemoPublishingService.publishToTeam(
                id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 当日の WORK メモをまとめてチームタイムラインに投稿する（日次まとめ投稿）。
     *
     * <p>postedTeamId が null のメモのみ対象（重複投稿防止）。0件は 400。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 投稿先チーム側を認可し、
     * 投稿対象は自己スコープに限定する。
     * {@code ActionMemoPublishingService#publishDailyToTeam}（ActionMemoPublishingService.java:244）が
     * リクエストの {@code team_id} について所属チーム検証
     * （ActionMemoPublishingService.java:251）を行い、対象メモは
     * 認証主体の当日 WORK メモのみを取得する（ActionMemoPublishingService.java:256）。
     * 非所属チームへの投稿は 404 で秘匿する。</p>
     */
    @PostMapping("/publish-daily-to-team")
    @Operation(summary = "当日 WORK メモをチームタイムラインに一括投稿（日次まとめ）")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "投稿成功")
    public ResponseEntity<ApiResponse<PublishDailyToTeamResponse>> publishDailyToTeam(
            @Valid @RequestBody PublishDailyToTeamRequest request) {
        PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(
                request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 投稿先として選択可能なチーム一覧を取得する。
     *
     * <p>ユーザーが所属するチームの一覧を返す。
     * {@code is_default: true} はデフォルト投稿先として設定されているチーム。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code ActionMemoScopeService#getAvailableTeams}（ActionMemoScopeService.java:40）は
     * 認証主体の {@code user_roles} 行から所属チームを解決するため
     * （ActionMemoScopeService.java:42）、返るのは自分の所属チームのみ。
     * 絞り込みキーをリクエストで指定する余地がない。</p>
     */
    @GetMapping("/available-teams")
    @Operation(summary = "投稿先チーム一覧取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<AvailableTeamResponse>>> getAvailableTeams() {
        List<AvailableTeamResponse> response = actionMemoScopeService.getAvailableTeams(
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Phase 5-2: 組織スコープ投稿先として選択可能な組織一覧を取得する。
     *
     * <p>ユーザーが所属する組織の一覧を返す。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code ActionMemoScopeService#getAvailableOrgs}（ActionMemoScopeService.java:68）は
     * 認証主体の {@code user_roles} 行から所属組織を解決するため
     * （ActionMemoScopeService.java:69）、返るのは自分の所属組織のみ。</p>
     */
    @GetMapping("/available-orgs")
    @Operation(summary = "組織スコープ投稿先組織一覧取得（Phase 5-2）")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<AvailableOrgResponse>>> getAvailableOrgs() {
        List<AvailableOrgResponse> response = actionMemoScopeService.getAvailableOrgs(
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 自己スコープ。
     * {@code ActionMemoAnalyticsService#getMoodStats}（ActionMemoAnalyticsService.java:43）は
     * 認証主体の userId を必須条件に集計対象を取得する
     * （ActionMemoAnalyticsService.java:44-45）。リクエストパラメータは期間のみで、
     * 集計対象ユーザーを指定する余地がない。</p>
     */
    @GetMapping("/mood-stats")
    @Operation(summary = "気分集計取得")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<MoodStatsResponse>> getMoodStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        MoodStatsResponse response = actionMemoAnalyticsService.getMoodStats(
                SecurityUtils.getCurrentUserId(), from, to);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Phase 5-1: メモに紐付く監査ログを取得する（折りたたみUI用）。
     *
     * <p>自分のメモのみ取得可能。最新10件を返す。
     * {@code ActionMemoAuditLogResponse} に変換して返すことで、
     * フロントエンドに不要なフィールド（IP アドレス・セッションハッシュ等）を露出しない。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: メモの監査ログ参照は作成者本人に限定する。
     * {@code ActionMemoService#getMemoAuditLogs}（ActionMemoService.java:237）が
     * 監査ログ検索の前に {@code findOwnMemoOrThrow}（ActionMemoService.java:508）を通すため、
     * 他ユーザーのメモ ID を指定しても 404 となり監査ログは返らない。</p>
     */
    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "メモ監査ログ取得（Phase 5-1）")
    @AuthorizedInService
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
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: entity 由来のスコープで管理者権限を要求する。
     * {@code ActionMemoAdminService#revertTodoCompletion}（ActionMemoAdminService.java:76）は
     * メモを取得したうえで <b>メモ entity の {@code postedTeamId}</b> を認可のスコープとして用い、
     * 呼び出し者が当該チームの ADMIN / DEPUTY_ADMIN であることを要求する
     * （ActionMemoAdminService.java:81-83）。この判定は {@code completesTodo} 等の
     * 業務状態の検証より前に行うため、権限のない呼び出し者にメモの状態は開示されない。
     * スコープをリクエストから受け取らないため
     * 投稿先チームの偽装はできない。権限のない呼び出しは 403。</p>
     */
    @DeleteMapping("/{id}/complete-todo")
    @Operation(summary = "TODO 差し戻し（Phase 4-β）— チーム管理者のみ")
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "差し戻し成功")
    public ResponseEntity<Void> revertTodoCompletion(@PathVariable Long id) {
        actionMemoAdminService.revertTodoCompletion(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
