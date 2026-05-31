package com.mannschaft.app.market.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.market.dto.MarketRegionNodeResponse;
import com.mannschaft.app.market.dto.MarketSummaryResponse;
import com.mannschaft.app.market.service.MarketQueryService;
import com.mannschaft.app.recruitment.dto.RecruitmentCategoryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentCategoryService;
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

import java.util.List;

/**
 * F22.1 市（Market）公開閲覧 Controller（02_api_design §2 / §3）。
 *
 * <p>本コントローラは<strong>すべて認証不要</strong>（permitAll・PII 抑制 DTO）。
 * {@code SecurityConfig} で {@code /api/v1/public/market/**} の GET 5 本（listings/listings*&#47;regions/summary/categories）を permitAll 登録済み。
 * レート制限は {@code PublicApiRateLimitFilter} が担う（market パスを追加済み）。</p>
 *
 * <p>市の札立て・応募・取下げは既存 recruitment API が担う。本コントローラは
 * <strong>読み取り集約のみ</strong>（README §1）。</p>
 */
@RestController
@RequestMapping("/api/v1/public/market")
@Tag(name = "F22.1 市（Market）公開閲覧", description = "地域×ジャンルで束ねた募集の公開ビュー（未ログイン可・PII抑制）")
@RequiredArgsConstructor
public class MarketController {

    private final MarketQueryService marketQueryService;

    /**
     * ジャンル（カテゴリ）マスタ取得用。市は実体を持たず recruitment のカテゴリマスタを共有する
     * （{@code MarketQueryService} が既にカテゴリ解決で recruitment を参照する前例に倣う）。
     */
    private final RecruitmentCategoryService recruitmentCategoryService;

    /**
     * 市の札一覧（地域×ジャンル×状態フィルタ・PII 抑制・§3.1）。
     *
     * @param prefecture        都道府県コード（任意）
     * @param city              市区町村コード（任意・prefecture と整合）
     * @param categoryId        ジャンル（任意）
     * @param keyword           タイトル部分一致（任意）
     * @param includeRegionNone 地域未指定の札も含めるか（既定 true）
     * @param page              ページ番号（既定 0）
     * @param size              ページサイズ（既定 20）
     * @return PII 抑制済みの公開札ページ
     */
    @GetMapping("/listings")
    @Operation(summary = "市の札一覧",
            description = "未ログインで実行可能。visibility=PUBLIC かつ status IN (OPEN,FULL) の札を返す。"
                    + "city 指定でその市区町村、prefecture のみで配下市区町村をロールアップ。")
    public ResponseEntity<PagedResponse<MarketListingResponse>> listListings(
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String city,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "include_region_none", defaultValue = "true") boolean includeRegionNone,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MarketListingResponse> result = marketQueryService.searchListings(
                prefecture, city, categoryId, keyword, includeRegionNone, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 市の公開札詳細（§3.2）。非公開・不在は 404（存在秘匿）。
     *
     * @param id 札ID
     * @return PII 抑制済みの公開札詳細
     */
    @GetMapping("/listings/{id}")
    @Operation(summary = "市の公開札詳細",
            description = "未ログインで実行可能。visibility != PUBLIC / 不在は 404 で存在秘匿。")
    public ResponseEntity<ApiResponse<MarketListingResponse>> getListing(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(marketQueryService.getListing(id)));
    }

    /**
     * 地域ファサード（§3.3）。prefecture 未指定なら都道府県、指定なら配下市区町村。
     *
     * @param prefecture 都道府県コード（任意）
     * @return 地域ノードリスト
     */
    @GetMapping("/regions")
    @Operation(summary = "市の地域一覧",
            description = "未ログインで実行可能。prefecture 未指定で都道府県47件、指定で配下市区町村一覧。")
    public ResponseEntity<ApiResponse<List<MarketRegionNodeResponse>>> getRegions(
            @RequestParam(required = false) String prefecture) {
        return ResponseEntity.ok(ApiResponse.of(marketQueryService.getRegions(prefecture)));
    }

    /**
     * 地域別の立っている札件数（§3.4）。
     *
     * @return 都道府県別・市区町村別の件数サマリ
     */
    @GetMapping("/summary")
    @Operation(summary = "市の地域別件数",
            description = "未ログインで実行可能。地域ノードごとの立っている札の件数（PII なし）。")
    public ResponseEntity<ApiResponse<MarketSummaryResponse>> getSummary() {
        return ResponseEntity.ok(ApiResponse.of(marketQueryService.getSummary()));
    }

    /**
     * 市のジャンル（カテゴリ）マスタ一覧（§3.6）。
     *
     * <p>未ログインで実行可能。市一覧ページのジャンルフィルタが認証必須 API
     * （{@code /api/v1/recruitment-categories}）を直叩きして 401 で市ページごと
     * ログインへ飛ばされていた不具合を根治するため新設（公開ページは公開 API のみに依存させる）。</p>
     *
     * <p>返すのは全テナント共通の固定カテゴリマスタ（i18n キー込み・表示順・PII なし）。
     * recruitment 層の {@link RecruitmentCategoryService#listCategories()} に委譲する。</p>
     *
     * @return アクティブカテゴリを表示順で並べた配列（camelCase）
     */
    @GetMapping("/categories")
    @Operation(summary = "市のジャンル一覧",
            description = "未ログインで実行可能。全テナント共通の固定カテゴリマスタ（i18nキー込み・表示順・PIIなし）を返す。")
    public ResponseEntity<ApiResponse<List<RecruitmentCategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.of(recruitmentCategoryService.listCategories()));
    }
}
