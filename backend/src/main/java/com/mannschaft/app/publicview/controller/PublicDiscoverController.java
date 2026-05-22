package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.publicview.dto.PublicOrganizationSearchResultResponse;
import com.mannschaft.app.publicview.dto.PublicTeamSearchResultResponse;
import com.mannschaft.app.publicview.service.PublicOrganizationSearchQueryService;
import com.mannschaft.app.publicview.service.PublicTeamSearchQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F19.1 Phase 4 公開チーム・組織検索 Controller。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §7.x Phase 4</p>
 *
 * <p>このコントローラは<strong>認証不要</strong>（permitAll）。
 * SecurityConfig で {@code /api/v1/public/teams/search} / {@code /api/v1/public/organizations/search}
 * の GET メソッドを permitAll として登録済み。</p>
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter}
 * が担う（F19.1 Phase 4 で対象パターンを拡張済み）。</p>
 *
 * <p><strong>IDOR 対策</strong>: 検索結果は PUBLIC かつ未 archive / 未削除のチーム・組織のみ返す。
 * PRIVATE や archived のエンティティは結果に含めない。</p>
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開チーム・組織検索 API (F19.1 Phase 4)")
@RequiredArgsConstructor
public class PublicDiscoverController {

    private final PublicTeamSearchQueryService publicTeamSearchQueryService;
    private final PublicOrganizationSearchQueryService publicOrganizationSearchQueryService;

    /**
     * 公開チームを検索する。
     *
     * <p>keyword / prefecture でフィルタリングし、最近投稿があるチームを優先して返す。</p>
     *
     * @param keyword    チーム名・読み仮名の部分一致キーワード（省略可）
     * @param prefecture 都道府県名の完全一致絞り込み（省略可）
     * @param pageable   ページング情報（デフォルト: size=20, sort=lastPostDate DESC）
     * @return PUBLIC チームの検索結果ページ
     */
    @GetMapping("/teams/search")
    @Operation(
            summary = "公開チーム検索",
            description = "未ログインでも実行可能。keyword / prefecture でフィルタリングし、"
                    + "最近投稿があるチームを優先する（lastPostDate DESC NULLS LAST）。"
                    + "visibility=PUBLIC かつ未 archive / 未削除のチームのみ返す。")
    public ResponseEntity<Page<PublicTeamSearchResultResponse>> searchTeams(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String prefecture,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return ResponseEntity.ok(
                publicTeamSearchQueryService.search(keyword, prefecture, pageable));
    }

    /**
     * 公開組織を検索する。
     *
     * <p>keyword / prefecture でフィルタリングし、最近投稿がある組織を優先して返す。</p>
     *
     * @param keyword    組織名・読み仮名の部分一致キーワード（省略可）
     * @param prefecture 都道府県名の完全一致絞り込み（省略可）
     * @param pageable   ページング情報（デフォルト: size=20, sort=name ASC）
     * @return PUBLIC 組織の検索結果ページ
     */
    @GetMapping("/organizations/search")
    @Operation(
            summary = "公開組織検索",
            description = "未ログインでも実行可能。keyword / prefecture でフィルタリングし、"
                    + "最近投稿がある組織を優先する（lastPostDate DESC NULLS LAST）。"
                    + "visibility=PUBLIC かつ未 archive / 未削除の組織のみ返す。")
    public ResponseEntity<Page<PublicOrganizationSearchResultResponse>> searchOrganizations(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String prefecture,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return ResponseEntity.ok(
                publicOrganizationSearchQueryService.search(keyword, prefecture, pageable));
    }
}
