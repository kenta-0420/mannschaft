package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.service.VillageFeedService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F17.1 Phase 1 B10 — ダッシュボード村フィード Controller（§4.13）。
 *
 * <p>{@code GET /api/v1/me/village-feed?limit=20}</p>
 *
 * <p>認証ユーザーのピン留め村を横断して「最新動き」を集約して返す。
 * ピン未登録なら空配列を返す。</p>
 */
@RestController
@RequestMapping("/api/v1/me/village-feed")
@Tag(name = "ダッシュボード村フィード (F17.1)",
        description = "Phase 1: ピン村横断のタイムライン・井戸端集約")
@RequiredArgsConstructor
public class VillageFeedController {

    private final VillageFeedService feedService;

    /**
     * ピン留め村の最新動きを集約して返す。
     *
     * <p>認可は {@link VillageFeedService#build} 内で実施する。ピン一覧は認証主体のピン行に
     * 束縛され、村内コンテンツ（井戸端メッセージ・タイムライン投稿・掲示板スレッド）は
     * 呼び出しユーザーが<b>現役の村人である村</b>のみを集約対象とする
     * （{@code PostingIdentityService#getActiveVillageIdsByUser} が退村・BAN 済みを除外する）。</p>
     */
    @AuthorizedInService
    @GetMapping
    @Operation(summary = "ピン留め村の最新動きをダッシュボード向けに集約取得（本文は村人である村のみ）")
    public ApiResponse<VillageFeedResponse> feed(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(feedService.build(actorUserId, limit));
    }
}
