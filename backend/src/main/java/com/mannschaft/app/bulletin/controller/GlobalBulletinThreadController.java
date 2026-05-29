package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam("scope_id") Long scopeId,
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
        } else if (categoryId != null) {
            result = threadService.listThreadsByCategory(type, scopeId, categoryId, currentUserId, pageable);
        } else {
            result = threadService.listThreads(type, scopeId, currentUserId, pageable);
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
