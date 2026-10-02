package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.VillageEventArchiveResponse;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import com.mannschaft.app.village.service.VillageEventArchiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17.2 Wave2 ⑦ 村史（行事アーカイブ）Controller（設計書 §7.4・Wave2 追補）。
 *
 * <p>祭・歳時記・寄合の確定記録（村史 = {@code village_event_archives}）を read-only で提供する。
 * 編纂（書き込み）は {@link VillageEventArchiveService#archiveFestival} がバッチ経由でのみ行うため、
 * 本 Controller は一覧取得の GET のみを持つ（2026-07-21 マスター裁定・§7.1 で「村史」タブの帰属が
 * 行事アーカイブへ確定したことに伴う読み出しEPの追補）。</p>
 *
 * <h2>認可</h2>
 * <p>{@code @PreAuthorize("isAuthenticated()")} で未認証を弾いたうえで、村スコープの実効認可は
 * {@link VillageEventArchiveService#listArchives} が担う。判定は<b>村掲示板と同一の閲覧認可</b>
 * （{@code VillageBulletinAccessService.checkVillageBulletinViewAccess}）に委譲する
 * （設計書 §7.4「村人（閲覧・掲示板と同一の閲覧認可）」・村お祭り Controller と同じ方針）。
 * 認可ガードを Controller ではなく Service の public 入口へ置くのは、バッチ等の別経路から
 * 呼ばれても認可が抜けないようにするため。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET /api/v1/villages/{villageId}/event-archives?sourceType=} —
 *       村史一覧（{@code archived_at} 降順・ページング・種別絞り込み可）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/event-archives")
@Tag(name = "村史 (F17.2 Wave2 ⑦)",
     description = "行事アーカイブ（祭/歳時記/寄合の確定記録）の read-only 一覧（2026-07-21 裁定・§7.1）")
@RequiredArgsConstructor
public class VillageEventArchiveController {

    /** 既定ページサイズ（Service 側の上限 100 と同期・設計書 §13.5）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VillageEventArchiveService archiveService;

    /**
     * 村史（行事アーカイブ）一覧を取得する（{@code archived_at} 降順）。
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "村史（行事アーカイブ）一覧を取得する（archived_at 降順・sourceType 絞り込み可）")
    public ApiResponse<List<VillageEventArchiveResponse>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "sourceType", required = false) VillageEventArchiveSourceType sourceType,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_PAGE_SIZE : size);
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<VillageEventArchiveResponse> list =
                archiveService.listArchives(villageId, sourceType, actorUserId, pageable);
        return ApiResponse.of(list);
    }
}
