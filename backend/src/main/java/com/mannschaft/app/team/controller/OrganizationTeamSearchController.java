package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.exception.OrganizationNotFoundException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.dto.TeamPublicSummaryResponse;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.dto.TeamSearchResultResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.service.TeamSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F15.4: 組織内チーム（店舗）検索コントローラ。
 *
 * <p>設計書: {@code docs/features/F15.4_team_store_search_within_org.md} §3 / §4.3
 *
 * <p>このコントローラは <strong>未ログインアクセス可</strong>（permitAll）であり、
 * 権限スコープを明確化するため既存 {@code TeamController} から分離した実装。
 *
 * <p>レート制限は {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter}
 * が担う（未ログイン 30 req/min/IP・ログイン 120 req/min/user）。
 * ※ クラス名遷移: {@code OrganizationTeamSearchRateLimitFilter}
 *   → F15.4 Phase 5-α {@code PublicTeamApiRateLimitFilter}
 *   → F19.1 Phase 1 {@link com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter}。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgPublicId}/teams")
@Tag(name = "組織内チーム検索 (F15.4)")
@RequiredArgsConstructor
public class OrganizationTeamSearchController {

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** sort パラメータのホワイトリスト（許可外は 400）。 */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("nameKana", "name", "createdAt");

    /** size パラメータの上限。 */
    private static final int MAX_PAGE_SIZE = 50;
    /** size パラメータの下限。 */
    private static final int MIN_PAGE_SIZE = 1;

    private final TeamSearchService teamSearchService;
    private final AccessControlService accessControlService;
    private final OrganizationService organizationService;
    /** 画像 URL 根治 Phase 1: 生 R2 キー → 署名付き表示 URL の解決を担う共通部品。 */
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 組織配下のチーム（店舗）を検索する。
     *
     * <p>権限分岐:
     * <ul>
     *   <li>当該組織メンバー → {@link TeamSearchResultResponse}（詳細版）</li>
     *   <li>未ログイン／非メンバー → {@link TeamPublicSummaryResponse}（抑制版）</li>
     * </ul>
     *
     * <p>組織が PUBLIC 以外で非メンバー／未ログインの場合は
     * エニュメレーション対策で 404 を返す（{@code TeamSearchService} 内部判定）。
     *
     * @param orgPublicId    組織の公開 UUID
     * @param keyword        部分一致キーワード（{@code name} または {@code name_kana}）
     * @param prefecture     都道府県名称（完全一致。{@code prefectureCode} 未指定時のフォールバック）
     * @param city           市町村名称（完全一致。{@code prefecture} 未指定時は無視）
     * @param template       業種テンプレート（完全一致）
     * @param prefectureCode 都道府県コード（F22.1 dual-support：指定時は名称より優先）
     * @param cityCode       市区町村コード（F22.1 dual-support：指定時は名称より優先）
     * @param page           ページ番号（0 起点、既定 0）
     * @param size           ページサイズ（1〜50、既定 20）
     * @param sort           ソート指定（{@code field,direction} 形式。既定 {@code nameKana,asc}）
     * @return ページング済み検索結果
     */
    @GetMapping("/search")
    @Operation(summary = "組織内チーム（店舗）検索",
            description = "未ログインでも実行可能。組織メンバーには詳細版、非メンバー／未ログインには抑制版 DTO を返す。"
                    + "F22.1: prefectureCode/cityCode 指定時はコード優先、未指定なら名称（prefecture/city）にフォールバック（dual-support）。")
    public ResponseEntity<PagedResponse<?>> search(
            @PathVariable String orgPublicId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String prefectureCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "nameKana,asc") String sort
    ) {
        // 1. バリデーション
        validatePageSize(size);
        validatePage(page);
        Sort sortSpec = parseSort(sort);

        // 2. Pageable 生成
        Pageable pageable = PageRequest.of(page, size, sortSpec);

        // 3. 検索条件構築（F22.1 dual-support: code 優先・名称フォールバック）
        TeamSearchCriteria criteria = new TeamSearchCriteria(
                keyword, prefecture, city, template, prefectureCode, cityCode);

        // 4. 現在ユーザー（未ログイン許容）
        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();

        // 5. publicId → 内部 orgId 解決
        Long orgId = organizationService.resolveOrgId(orgPublicId);

        // 6. 検索実行（TeamSearchService 内で 404 判定を含む）
        Page<TeamEntity> resultPage = teamSearchService.search(orgId, criteria, currentUserId, pageable);

        // 7. メンバー判定で DTO 切り替え
        boolean isMember = currentUserId != null
                && accessControlService.isMember(currentUserId, orgId, SCOPE_ORGANIZATION);

        PagedResponse<?> body;
        if (isMember) {
            List<TeamSearchResultResponse> content = resultPage.getContent().stream()
                    // 画像 URL 根治 Phase 1: icon/banner を署名付き表示 URL へ解決して渡す。
                    .map(team -> TeamSearchResultResponse.from(
                            team,
                            mediaUrlResolver.resolve(team.getIconUrl()),
                            mediaUrlResolver.resolve(team.getBannerUrl())))
                    .toList();
            body = PagedResponse.of(content, buildMeta(resultPage, page, size));
        } else {
            List<TeamPublicSummaryResponse> content = resultPage.getContent().stream()
                    // 画像 URL 根治 Phase 1: icon を署名付き表示 URL へ解決して渡す（抑制版はバナーなし）。
                    .map(team -> TeamPublicSummaryResponse.from(
                            team, mediaUrlResolver.resolve(team.getIconUrl())))
                    .toList();
            body = PagedResponse.of(content, buildMeta(resultPage, page, size));
        }
        return ResponseEntity.ok(body);
    }

    // ────────────────────────────────────────────────────────────
    // バリデーションヘルパー
    // ────────────────────────────────────────────────────────────

    private void validatePageSize(int size) {
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
        }
    }

    private void validatePage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
    }

    /**
     * {@code "field,direction"} 形式の sort 値をパースする。
     * 設計書 §3.2 のホワイトリスト ({@code nameKana,asc} / {@code name,asc} / {@code createdAt,desc}) のみ許可。
     */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "nameKana");
        }
        String[] parts = sort.split(",");
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("Invalid sort format: " + sort);
        }
        String field = parts[0].trim();
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new IllegalArgumentException("Sort field not allowed: " + field);
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            String dir = parts[1].trim().toLowerCase();
            if ("asc".equals(dir)) {
                direction = Sort.Direction.ASC;
            } else if ("desc".equals(dir)) {
                direction = Sort.Direction.DESC;
            } else {
                throw new IllegalArgumentException("Sort direction must be asc or desc: " + dir);
            }
        }
        return Sort.by(direction, field);
    }

    private PagedResponse.PageMeta buildMeta(Page<?> page, int requestedPage, int requestedSize) {
        return new PagedResponse.PageMeta(
                page.getTotalElements(),
                requestedPage,
                requestedSize,
                page.getTotalPages()
        );
    }

    // ────────────────────────────────────────────────────────────
    // 例外ハンドラ
    // ────────────────────────────────────────────────────────────

    /**
     * 組織が存在しない／削除済み／未ログイン者から見て可視性違反 → 404
     * （エニュメレーション対策のため 403 ではなく 404 を返す）
     */
    @ExceptionHandler(OrganizationNotFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleOrganizationNotFound(
            OrganizationNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.of(Map.of("error", "Organization not found")));
    }

    /**
     * クエリパラメータバリデーション違反 → 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBadRequest(
            IllegalArgumentException ex) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.of(Map.of("error", ex.getMessage())));
    }
}
