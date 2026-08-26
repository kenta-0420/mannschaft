package com.mannschaft.app.bulletin.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ChangePriorityRequest;
import com.mannschaft.app.bulletin.dto.GlobalCreateThreadRequest;
import com.mannschaft.app.bulletin.dto.SetArchiveRequest;
import com.mannschaft.app.bulletin.dto.SetLockRequest;
import com.mannschaft.app.bulletin.dto.ReadAllRequest;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.dto.SetPinRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.service.BulletinReadStatusService;
import com.mannschaft.app.bulletin.service.BulletinScopeIdResolver;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 掲示板スレッド「グローバル方式」コントローラー（F17.1 村掲示板グローバル方式 §3.12.1）。
 *
 * <p>パス変数方式（{@code /api/v1/{scopeType}/{scopeId}/bulletin/threads}）とは別に、
 * クエリパラメータでスコープを指定する経路を提供する。FE は村ページから
 * {@code scope_type=VILLAGE&scope_id=0&scope_village_id=<UUID>} の形で叩く。</p>
 *
 * <p>本コントローラーは <b>読取（GET 一覧 / GET 詳細）専用</b>。スレッドの作成・更新・削除・
 * ピン/ロック/アーカイブ・既読・返信は後続足軽が同 prefix に追加する。</p>
 *
 * <h2>スコープ分岐（一覧）</h2>
 * <ul>
 *   <li>{@code VILLAGE}: {@code scope_village_id} 必須。村可視性認可 → 村スレッド一覧
 *       （{@link BulletinThreadService#listVillageThreads}）。{@code category_id} で絞り込み可。</li>
 *   <li>{@code ORGANIZATION / TEAM / PERSONAL}: {@code scope_id} で既存経路へ委譲。</li>
 * </ul>
 *
 * <h2>詳細</h2>
 * <p>FE は {@code GET /api/v1/bulletin/threads/{threadId}} をスコープ情報なしで叩くため、
 * {@link BulletinThreadService#getThreadGlobal} が threadId からスコープを逆引きし、
 * VILLAGE なら可視性認可、それ以外なら所属認可を適用する。他村のスレッドは認可で弾かれる。</p>
 *
 * <p>不正な {@code scope_type}、VILLAGE での {@code scope_village_id} 欠落は
 * {@link CommonErrorCode#COMMON_001}（400）として弾く（500 を撒かない）。</p>
 */
@RestController
@RequestMapping("/api/v1/bulletin/threads")
@Tag(name = "掲示板スレッド（グローバル）", description = "F17.1 村掲示板グローバル方式 スレッド一覧・詳細")
@RequiredArgsConstructor
public class GlobalBulletinThreadController {

    private final BulletinThreadService threadService;
    private final BulletinReadStatusService readStatusService;
    private final ObjectMapper objectMapper;
    private final BulletinScopeIdResolver scopeIdResolver;

    /**
     * スレッド一覧を取得する（グローバル方式）。
     *
     * @param scopeType      スコープ種別（{@code VILLAGE / ORGANIZATION / TEAM / PERSONAL}）
     * @param scopeId        スコープ ID（VILLAGE 時は 0）
     * @param scopeVillageId 村 ID（VILLAGE 時必須・それ以外は無視）
     * @param categoryId     カテゴリ ID（任意）
     * @param page           ページ番号（0 始まり）
     * @param size           ページサイズ
     * @return スレッド一覧（{@code { data: [...], meta: {...} }}）
     */
    @GetMapping
    @Operation(summary = "スレッド一覧（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ThreadResponse>> listThreads(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") String scopeId,
            @RequestParam(value = "scope_village_id", required = false) UUID scopeVillageId,
            @RequestParam(value = "category_id", required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScopeType type = parseScopeType(scopeType);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        PageRequest pageable = PageRequest.of(page, size);

        Page<ThreadResponse> result;
        if (type == ScopeType.VILLAGE) {
            if (scopeVillageId == null) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
            result = threadService.listVillageThreads(scopeVillageId, categoryId, currentUserId, pageable);
        } else {
            Long resolvedScopeId = scopeIdResolver.resolve(type, scopeId);
            if (categoryId != null) {
                result = threadService.listThreadsByCategory(type, resolvedScopeId, categoryId, currentUserId, pageable);
            } else {
                result = threadService.listThreads(type, resolvedScopeId, currentUserId, pageable);
            }
        }

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * スレッド詳細を取得する（グローバル方式）。
     *
     * <p>threadId のみで叩かれるため、サービス層がスコープを逆引きして認可する。
     * 村スレッドは村可視性認可、それ以外は所属認可を適用する（他村のスレッドは弾かれる）。</p>
     *
     * @param threadId スレッド ID
     * @return スレッド詳細（{@code { data: {...} }}）
     */
    @GetMapping("/{threadId}")
    @Operation(summary = "スレッド詳細（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> getThread(@PathVariable Long threadId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ThreadResponse response = threadService.getThreadGlobal(threadId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================================================
    // 書込・モデレーション（F17.1 村掲示板グローバル方式）
    // ========================================================================

    /**
     * スレッドを作成する（グローバル方式・JSON）。
     *
     * <p>body に {@code scopeType / scopeId(VILLAGE 時は 0) / scopeVillageId} と本文フィールドを同梱する。
     * VILLAGE は村メンバー必須 + 投稿主体検証、ORG/TEAM/PERSONAL は所属認可をサービス層が担う。</p>
     *
     * @param request 作成リクエスト
     * @return 作成されたスレッド（{@code { data: {...} }}・201）
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code createThreadGlobal} は既存の {@code createThread}（VILLAGE/ORG/TEAM/PERSONAL の
     * 認可・投稿主体検証を内包）へ委譲する。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "スレッド作成（グローバル・JSON）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> createThread(
            @Valid @RequestBody GlobalCreateThreadRequest request) {
        return doCreate(request);
    }

    /**
     * スレッドを作成する（グローバル方式・multipart）。
     *
     * <p>FE は添付ファイルがある場合 {@code multipart/form-data} で {@code data}（JSON 文字列）+
     * {@code files[]} を送る（{@code useBulletinThreads.ts createThread()}）。
     * 添付ファイルの永続化は bulletin ドメインに未実装のため、本フェーズでは {@code data} のみを処理し
     * {@code files[]} は受理するが保存しない（申し送り事項。後続フェーズで添付基盤を整備する）。</p>
     *
     * @param dataJson スレッド本文・スコープ情報を含む JSON 文字列（{@code data} パート）
     * @param files    添付ファイル（任意・本フェーズ未保存）
     * @return 作成されたスレッド（{@code { data: {...} }}・201）
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code doCreate} 経由で {@code createThreadGlobal}（＝既存 {@code createThread}）に委譲する。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "スレッド作成（グローバル・multipart）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> createThreadMultipart(
            @RequestPart("data") String dataJson,
            @RequestPart(value = "files[]", required = false) MultipartFile[] files) {
        GlobalCreateThreadRequest request;
        try {
            request = objectMapper.readValue(dataJson, GlobalCreateThreadRequest.class);
        } catch (JsonProcessingException e) {
            // 不正な data パートは 400（500 を撒かない）
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        // files[] は本フェーズ未保存（添付基盤未整備。申し送り）。
        return doCreate(request);
    }

    /**
     * 作成処理本体（JSON / multipart 共通）。scope_type をパースしてサービスへ委譲する。
     */
    private ResponseEntity<ApiResponse<ThreadResponse>> doCreate(GlobalCreateThreadRequest request) {
        ScopeType type = parseScopeType(request.getScopeType());
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Long scopeId = request.getScopeId() != null ? request.getScopeId() : 0L;
        ThreadResponse response = threadService.createThreadGlobal(
                type, scopeId, currentUserId, request.toCreateThreadRequest());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * スレッドを更新する（グローバル方式）。認可=投稿者本人 or 村モデレーター（VILLAGE）／既存所属認可。
     *
     * @param threadId スレッド ID
     * @param request  更新リクエスト（{@code title / body / priority}）
     * @return 更新されたスレッド（{@code { data: {...} }}）
     */
    @PutMapping("/{threadId}")
    @Operation(summary = "スレッド更新（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> updateThread(
            @PathVariable Long threadId,
            @Valid @RequestBody UpdateThreadRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ThreadResponse response = threadService.updateThreadGlobal(threadId, currentUserId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドを論理削除する（グローバル方式）。認可=投稿者本人 or 村モデレーター（VILLAGE）／既存所属認可。
     *
     * @param threadId スレッド ID
     * @return 204 No Content
     */
    @DeleteMapping("/{threadId}")
    @Operation(summary = "スレッド削除（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteThread(@PathVariable Long threadId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        threadService.deleteThreadGlobal(threadId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * スレッドの優先度を変更する（グローバル方式）。村モデレーター／既存管理権限のみ。
     *
     * @param threadId スレッド ID
     * @param request  優先度リクエスト（{@code priority}）
     * @return 更新されたスレッド（{@code { data: {...} }}）
     */
    @PatchMapping("/{threadId}/priority")
    @Operation(summary = "スレッド優先度変更（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> changePriority(
            @PathVariable Long threadId,
            @Valid @RequestBody ChangePriorityRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ThreadResponse response = threadService.changePriorityGlobal(threadId, currentUserId, request.getPriority());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドのピン留め状態を設定する（set 方式・グローバル方式）。村モデレーター／既存管理権限のみ。
     *
     * @param threadId スレッド ID
     * @param request  ピン留めリクエスト（{@code pinned}・明示値）
     * @return 更新されたスレッド（{@code { data: {...} }}）
     */
    @PatchMapping("/{threadId}/pin")
    @Operation(summary = "スレッドピン留め設定（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> setPin(
            @PathVariable Long threadId,
            @RequestBody SetPinRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ThreadResponse response = threadService.setPinGlobal(threadId, currentUserId, request.resolvePinned());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドのロック状態を設定する（set 方式・グローバル方式）。村モデレーター／既存管理権限のみ。
     *
     * @param threadId スレッド ID
     * @param request  ロックリクエスト（{@code locked}・明示値）
     * @return 更新されたスレッド（{@code { data: {...} }}）
     */
    @PatchMapping("/{threadId}/lock")
    @Operation(summary = "スレッドロック設定（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> setLock(
            @PathVariable Long threadId,
            @RequestBody SetLockRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ThreadResponse response = threadService.setLockGlobal(threadId, currentUserId, request.resolveLocked());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * スレッドのアーカイブ状態を変更する（グローバル方式）。
     *
     * <p>body {@code { is_archived: true|false }}（snake_case）。認可は既存アーカイブ仕様に合わせ、
     * 村モデレーター（VILLAGE）／既存管理権限（ORG/TEAM）を要求する。</p>
     *
     * @param threadId スレッド ID
     * @param request  アーカイブリクエスト（{@code is_archived}・null は true 扱い）
     * @return 更新されたスレッド（{@code { data: {...} }}）
     */
    @PostMapping("/{threadId}/archive")
    @Operation(summary = "スレッドアーカイブ状態変更（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変更成功")
    public ResponseEntity<ApiResponse<ThreadResponse>> archive(
            @PathVariable Long threadId,
            @RequestBody(required = false) SetArchiveRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isArchived = request == null || request.resolveArchived();
        ThreadResponse response = threadService.archiveGlobal(threadId, currentUserId, isArchived);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ========================================================================
    // 既読系（F17.1 村掲示板グローバル方式）
    // ========================================================================

    /**
     * スレッドを既読にする（グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、サービス層がスコープを逆引きして認可する
     * （VILLAGE は村可視性認可、それ以外は所属認可）。既読済みの場合は何もしない（冪等）。</p>
     *
     * @param threadId スレッド ID
     * @return 201 Created
     */
    @PostMapping("/{threadId}/read")
    @Operation(summary = "既読マーク（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "既読成功")
    public ResponseEntity<Void> markRead(@PathVariable Long threadId) {
        readStatusService.markAsReadGlobal(threadId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * スコープ内の全スレッドを一括既読にする（グローバル方式）。
     *
     * <p>body に {@code scopeType / scopeId(VILLAGE 時は 0) / scopeVillageId} を渡す。VILLAGE は村可視性認可、
     * それ以外は所属認可を行ったうえで、未読スレッドをすべて既読化する。</p>
     *
     * @param request 一括既読リクエスト
     * @return 200 OK（{@code { data: { markedCount } }}）
     */
    @PostMapping("/read-all")
    @Operation(summary = "一括既読（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括既読成功")
    public ResponseEntity<ApiResponse<MarkAllReadResult>> markAllRead(
            @Valid @RequestBody ReadAllRequest request) {
        ScopeType type = parseScopeType(request.getScopeType());
        Long scopeId = request.getScopeId() != null ? request.getScopeId() : 0L;
        int marked = readStatusService.markAllAsReadGlobal(
                type, scopeId, request.getScopeVillageId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(new MarkAllReadResult(marked)));
    }

    /**
     * スレッドの既読者一覧を取得する（グローバル方式）。
     *
     * <p>{@code threadId} のみで叩かれるため、サービス層がスコープを逆引きして認可する。
     * 既読プライバシー（{@code read_tracking_mode}）と {@code filter=unread}（ADMIN/村モデレーターのみ）の
     * 制御はサービス層に委ねる。</p>
     *
     * @param threadId スレッド ID
     * @param filter   フィルタ（{@code "unread"} 指定時は ADMIN / 村モデレーターのみ）
     * @return 既読者一覧（{@code { data: [...] }}）
     */
    @GetMapping("/{threadId}/readers")
    @Operation(summary = "既読者一覧（グローバル）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ReadStatusResponse>>> listReaders(
            @PathVariable Long threadId,
            @RequestParam(required = false) String filter) {
        List<ReadStatusResponse> responses =
                readStatusService.listReadUsersGlobal(threadId, SecurityUtils.getCurrentUserId(), filter);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 一括既読の結果（新たに既読化したスレッド件数）。
     *
     * @param markedCount 新たに既読化したスレッド件数
     */
    public record MarkAllReadResult(int markedCount) {}

    /**
     * scope_type をパースする。不正値は {@link CommonErrorCode#COMMON_001}（400）に変換する。
     */
    private ScopeType parseScopeType(String scopeType) {
        try {
            return ScopeType.fromPathSegment(scopeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
