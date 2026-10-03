package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.VillageInternalSearchResponse;
import com.mannschaft.app.village.service.VillageSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 Phase 1 B10 — 村内検索 Controller（§4.12）。
 *
 * <p>{@code GET /api/v1/villages/{villageId}/search?q=&type=&page=&size=}</p>
 *
 * <h2>権限</h2>
 * <ul>
 *   <li>認証必須</li>
 *   <li>村人（{@code village_memberships} 行あり）のみ実行可</li>
 *   <li>非村人は 404（IDOR 対策）</li>
 * </ul>
 *
 * <h2>クエリパラメータ</h2>
 * <ul>
 *   <li>{@code q} 必須 / 最低 2 文字 / 不一致は 422 {@code VILLAGE_051}</li>
 *   <li>{@code type} 任意（{@code POST}/{@code MESSAGE}/{@code MEMBER}/{@code ALL}、デフォルト ALL）</li>
 *   <li>{@code page} デフォルト 0</li>
 *   <li>{@code size} デフォルト 20、上限 50</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/search")
@Tag(name = "村内検索 (F17.1)", description = "Phase 1: 投稿・メッセージ・メンバー横断検索")
@RequiredArgsConstructor
public class VillageSearchController {

    private final VillageSearchService searchService;

    /**
     * 認可は {@link VillageSearchService#search} 内で実施する。村の存在確認ののち
     * {@code requireVillageMember} が在籍かつ BAN 済みでないことを検証し、
     * 満たさない場合は {@code NOT_MEMBER}（404）で村の存在ごと秘匿する。
     */
    @AuthorizedInService
    @GetMapping
    @Operation(summary = "村内横断検索（投稿・メッセージ・メンバー／村人のみ）")
    public ApiResponse<VillageInternalSearchResponse> search(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "q") String q,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(searchService.search(villageId, q, type, page, size, actorUserId));
    }
}
