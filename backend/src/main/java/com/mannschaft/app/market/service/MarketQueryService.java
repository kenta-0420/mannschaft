package com.mannschaft.app.market.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.market.dto.MarketCategoryDto;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.market.dto.MarketOwnerDto;
import com.mannschaft.app.market.dto.MarketRegionDto;
import com.mannschaft.app.market.dto.MarketRegionNodeResponse;
import com.mannschaft.app.market.dto.MarketSummaryResponse;
import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.matching.service.RegionTranslationService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.util.LikeEscapeUtil;
import com.mannschaft.app.recruitment.visibility.RecruitmentListingVisibilityResolver;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F22.1 市（Market）集約: 公開閲覧・地域ファサード・件数集計の読み取り Service
 * （02_api_design §3 / README §3 論理ビュー）。
 *
 * <p>市は<strong>実体テーブルを持たない</strong>。{@code recruitment_listings} を
 * {@code visibility='PUBLIC' AND status IN (OPEN,FULL)} で絞った論理ビューを返す。
 * 書き込み・状態遷移は持たない（recruitment へ委譲）。</p>
 *
 * <p><strong>PII 抑制</strong>: レスポンスは {@link MarketListingResponse} 等の抑制 DTO のみ。
 * 作成者・応募者の個人情報は一切含めない（§04_security §1.3）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketQueryService {

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentListingVisibilityResolver listingVisibilityResolver;
    private final RecruitmentListingRegionRepository listingRegionRepository;
    private final RecruitmentCategoryRepository categoryRepository;
    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final RegionTranslationService regionTranslationService;
    private final MediaUrlResolver mediaUrlResolver;
    private final UserService userService;
    private final RoleService roleService;

    // =====================================================================
    // §3.1 一覧
    // =====================================================================

    /**
     * 市の公開札一覧を地域×ジャンル×キーワードで検索する。
     *
     * @param prefecture        都道府県コード（null=全国）
     * @param city              市区町村コード（null=県ロールアップ or 全国）
     * @param categoryId        ジャンル（null=全ジャンル）
     * @param keyword           タイトル部分一致（null=無条件）
     * @param includeRegionNone 地域未指定の札も含めるか（既定 true）
     * @param pageable          ページング
     * @return PII 抑制済みの公開札ページ
     */
    public Page<MarketListingResponse> searchListings(
            String prefecture, String city, Long categoryId,
            String keyword, boolean includeRegionNone, Pageable pageable) {
        return searchListings(prefecture, city, categoryId, keyword, includeRegionNone, pageable, null);
    }

    /**
     * 市の公開札一覧を地域×ジャンル×キーワードで検索する（多言語対応・F22.1 Phase2 E）。
     *
     * <p>札に付随する地域名（prefectureName / cityName）は {@code lang} の訳があれば訳名、
     * 無ければマスタ日本語名（fallback）。lang 未指定/ja は従来どおり日本語名。</p>
     *
     * @param lang 正規化済み言語コード（null=日本語マスタ名）
     */
    public Page<MarketListingResponse> searchListings(
            String prefecture, String city, Long categoryId,
            String keyword, boolean includeRegionNone, Pageable pageable, String lang) {
        return searchListings(
                prefecture, city, categoryId, keyword, includeRegionNone, pageable, lang, null);
    }

    /**
     * 市の公開札一覧を閲覧者別 owner 表示で検索する。
     *
     * @param viewerUserId 認証済み閲覧者 ID（未認証は null）
     */
    public Page<MarketListingResponse> searchListings(
            String prefecture, String city, Long categoryId,
            String keyword, boolean includeRegionNone, Pageable pageable, String lang,
            Long viewerUserId) {
        String normalizedPref = blankToNull(prefecture);
        String normalizedCity = blankToNull(city);
        // blankToNull → escape の順。null はエスケープせず透過する。
        // LIKE ワイルドカード（% / _ / \）をリテラル化し、フィルタ無効化を防ぐ（JPQL の ESCAPE '\' と対）。
        String normalizedKeyword = LikeEscapeUtil.escape(blankToNull(keyword));

        Page<RecruitmentListingEntity> page;
        if (viewerUserId == null) {
            page = listingRepository.searchMarketListings(
                    normalizedPref, normalizedCity, categoryId, normalizedKeyword,
                    includeRegionNone, pageable);
        } else {
            page = listingRepository.searchAccessibleMarketListings(
                    accessibleListingIdsOrSentinel(viewerUserId), normalizedPref, normalizedCity,
                    categoryId, normalizedKeyword, includeRegionNone, pageable);
        }

        // N+1 回避: ページ内の全札からカテゴリ/scope/地域コードを集約し、
        // それぞれ findAllById で 1 SQL ずつバルク解決して Map 引きでマッピングする。
        List<RecruitmentListingEntity> listings = page.getContent();
        MarketResolverMaps maps = buildResolverMaps(listings, lang, viewerUserId);
        return page.map(e -> toMarketListingResponse(e, maps));
    }

    /**
     * ページ内の全札に必要なマスタを一括取得した参照 Map 群（N+1 回避）。
     *
     * @param regionTranslations 地域コード→訳名（lang 未指定時は空。未訳コードは含まれない）
     * @param regionsByListing   札ID→地域中間行リスト（id 昇順・複数地域 N:N。F22.1 Phase2 D）
     */
    private record MarketResolverMaps(
            Map<Long, RecruitmentCategoryEntity> categories,
            Map<Long, TeamEntity> teams,
            Map<Long, OrganizationEntity> organizations,
            Map<Long, UserService.MarketOwnerIdentity> personalOwners,
            Set<Long> sharedAffiliationOwnerIds,
            Map<String, PrefectureEntity> prefectures,
            Map<String, CityEntity> cities,
            Map<String, String> regionTranslations,
            Map<Long, List<RecruitmentListingRegionEntity>> regionsByListing) {
    }

    private MarketResolverMaps buildResolverMaps(
            List<RecruitmentListingEntity> listings, String lang, Long viewerUserId) {
        Set<Long> categoryIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> orgIds = new LinkedHashSet<>();
        Set<Long> personalOwnerIds = new LinkedHashSet<>();
        Set<String> prefCodes = new LinkedHashSet<>();
        Set<String> cityCodes = new LinkedHashSet<>();

        // F22.1 Phase2 D: ページ内 listingId 群で中間表をバルク取得（N+1 回避）。
        List<Long> listingIds = listings.stream()
                .map(RecruitmentListingEntity::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, List<RecruitmentListingRegionEntity>> regionsByListing = listingIds.isEmpty()
                ? Map.of()
                : listingRegionRepository.findByListingIdInOrderByListingIdAscIdAsc(listingIds).stream()
                        .collect(Collectors.groupingBy(
                                RecruitmentListingRegionEntity::getListingId,
                                LinkedHashMap::new,
                                Collectors.toList()));

        for (RecruitmentListingEntity e : listings) {
            if (e.getCategoryId() != null) {
                categoryIds.add(e.getCategoryId());
            }
            if (e.getScopeId() != null) {
                switch (e.getScopeType()) {
                    case TEAM -> teamIds.add(e.getScopeId());
                    case ORGANIZATION -> orgIds.add(e.getScopeId());
                    case PERSONAL -> personalOwnerIds.add(e.getScopeId());
                }
            }
            // 旧単一列（代表・後方互換）由来のコード。
            if (e.getPrefectureCode() != null) {
                prefCodes.add(e.getPrefectureCode());
            }
            if (e.getCityCode() != null) {
                cityCodes.add(e.getCityCode());
            }
        }
        // 中間表由来の全地域コードも名前解決・訳の対象に含める。
        for (List<RecruitmentListingRegionEntity> rows : regionsByListing.values()) {
            for (RecruitmentListingRegionEntity r : rows) {
                if (r.getPrefectureCode() != null) {
                    prefCodes.add(r.getPrefectureCode());
                }
                if (r.getCityCode() != null) {
                    cityCodes.add(r.getCityCode());
                }
            }
        }

        Map<Long, RecruitmentCategoryEntity> categories = new LinkedHashMap<>();
        for (RecruitmentCategoryEntity c : categoryRepository.findAllById(categoryIds)) {
            categories.put(c.getId(), c);
        }
        Map<Long, TeamEntity> teams = new LinkedHashMap<>();
        for (TeamEntity t : teamRepository.findAllById(teamIds)) {
            teams.put(t.getId(), t);
        }
        Map<Long, OrganizationEntity> organizations = new LinkedHashMap<>();
        for (OrganizationEntity o : organizationRepository.findAllById(orgIds)) {
            organizations.put(o.getId(), o);
        }
        Map<Long, UserService.MarketOwnerIdentity> personalOwners =
                userService.getActiveMarketOwnerIdentities(personalOwnerIds);
        Set<Long> sharedAffiliationOwnerIds =
                roleService.findUserIdsSharingAffiliation(viewerUserId, personalOwnerIds);
        Map<String, PrefectureEntity> prefectures = new LinkedHashMap<>();
        for (PrefectureEntity p : prefectureRepository.findAllById(prefCodes)) {
            prefectures.put(p.getCode(), p);
        }
        Map<String, CityEntity> cities = new LinkedHashMap<>();
        for (CityEntity c : cityRepository.findAllById(cityCodes)) {
            cities.put(c.getCode(), c);
        }
        // 地域名の訳をまとめて1言語ぶんバルク取得（都道府県・市区町村コードを合算して1 SQL）。
        Set<String> allRegionCodes = new LinkedHashSet<>(prefCodes);
        allRegionCodes.addAll(cityCodes);
        Map<String, String> regionTranslations =
                regionTranslationService.resolveNames(allRegionCodes, lang);
        return new MarketResolverMaps(
                categories, teams, organizations, personalOwners, sharedAffiliationOwnerIds,
                prefectures, cities,
                regionTranslations, regionsByListing);
    }

    private MarketListingResponse toMarketListingResponse(
            RecruitmentListingEntity e, MarketResolverMaps maps) {
        // F22.1 Phase2 D: 中間表（N:N）から全地域を解決。後方互換のため代表（先頭）を region に残す。
        List<RecruitmentListingRegionEntity> rows =
                maps.regionsByListing().getOrDefault(e.getId(), List.of());
        List<MarketRegionDto> regions = rows.stream()
                .map(r -> resolveRegionFromMap(r.getPrefectureCode(), r.getCityCode(), maps))
                .filter(java.util.Objects::nonNull)
                .toList();

        // 代表地域: 中間表先頭を優先。中間表が空（旧データ・地域なし）の場合は旧単一列で後方互換。
        MarketRegionDto representative = regions.isEmpty()
                ? resolveRegionFromMap(e.getPrefectureCode(), e.getCityCode(), maps)
                : regions.get(0);

        return new MarketListingResponse(
                e.getId(),
                e.getTitle(),
                resolveCategoryFromMap(e.getCategoryId(), maps),
                resolveOwnerFromMap(e.getScopeType(), e.getScopeId(), e.getVisibility(), maps),
                representative,
                regions,
                e.getLocation(),
                e.getStartAt(),
                e.getApplicationDeadline(),
                e.getCapacity(),
                e.getConfirmedCount(),
                e.getStatus().name(),
                // Phase 1 では決済は常に false（謝礼決済は Phase 2）。
                Boolean.FALSE,
                e.getParticipationType().name());
    }

    private MarketCategoryDto resolveCategoryFromMap(Long categoryId, MarketResolverMaps maps) {
        if (categoryId == null) {
            return null;
        }
        RecruitmentCategoryEntity c = maps.categories().get(categoryId);
        return c == null
                ? new MarketCategoryDto(categoryId, null)
                : new MarketCategoryDto(c.getId(), categoryNameKey(c));
    }

    private MarketOwnerDto resolveOwnerFromMap(
            RecruitmentScopeType scopeType,
            Long scopeId,
            RecruitmentVisibility visibility,
            MarketResolverMaps maps) {
        return switch (scopeType) {
            case TEAM -> {
                TeamEntity t = maps.teams().get(scopeId);
                yield t == null ? new MarketOwnerDto("TEAM", scopeId, null, null)
                        : new MarketOwnerDto("TEAM", scopeId, t.getName(), mediaUrlResolver.resolve(t.getIconUrl()));
            }
            case ORGANIZATION -> {
                OrganizationEntity o = maps.organizations().get(scopeId);
                yield o == null ? new MarketOwnerDto("ORGANIZATION", scopeId, null, null)
                        : new MarketOwnerDto("ORGANIZATION", scopeId, o.getName(), mediaUrlResolver.resolve(o.getIconUrl()));
            }
            case PERSONAL -> {
                UserService.MarketOwnerIdentity owner = maps.personalOwners().get(scopeId);
                // 凍結・退会・不在・公開プロフィール無効は公開側へ倒さない。
                if (owner == null || (visibility == RecruitmentVisibility.PUBLIC
                        && !owner.publicProfileEnabled())) {
                    throw new BusinessException(MarketErrorCode.LISTING_NOT_FOUND);
                }
                boolean maySeeRealName = !owner.minor()
                        && maps.sharedAffiliationOwnerIds().contains(scopeId);
                String displayName = maySeeRealName
                        && owner.fullName() != null && !owner.fullName().isBlank()
                        ? owner.fullName() : owner.displayName();
                yield new MarketOwnerDto("PERSONAL", null, displayName, owner.avatarUrl());
            }
        };
    }

    private MarketRegionDto resolveRegionFromMap(
            String prefectureCode, String cityCode, MarketResolverMaps maps) {
        if (prefectureCode == null && cityCode == null) {
            return null;
        }
        // 訳 → マスタ日本語名 の順でフォールバック。
        String prefName = prefectureCode == null ? null
                : maps.regionTranslations().containsKey(prefectureCode)
                        ? maps.regionTranslations().get(prefectureCode)
                        : maps.prefectures().containsKey(prefectureCode)
                                ? maps.prefectures().get(prefectureCode).getName() : null;
        String cityName = cityCode == null ? null
                : maps.regionTranslations().containsKey(cityCode)
                        ? maps.regionTranslations().get(cityCode)
                        : maps.cities().containsKey(cityCode)
                                ? maps.cities().get(cityCode).getName() : null;
        return new MarketRegionDto(prefectureCode, prefName, cityCode, cityName);
    }

    // =====================================================================
    // §3.2 詳細
    // =====================================================================

    /**
     * 市の公開札詳細を取得する。{@code visibility != PUBLIC} / 不在は {@code MARKET_404}（存在秘匿）。
     *
     * @param id 札ID
     * @return PII 抑制済みの公開札詳細
     * @throws BusinessException {@code MARKET_404}（404 で存在秘匿）
     */
    public MarketListingResponse getListing(Long id) {
        return getListing(id, null);
    }

    /**
     * 市の公開札詳細を取得する（多言語対応・F22.1 Phase2 E）。地域名は {@code lang} に追従。
     *
     * @param id   札ID
     * @param lang 正規化済み言語コード（null=日本語マスタ名）
     * @return PII 抑制済みの公開札詳細
     * @throws BusinessException {@code MARKET_404}（404 で存在秘匿）
     */
    public MarketListingResponse getListing(Long id, String lang) {
        return getListing(id, lang, null);
    }

    /**
     * 市の公開札詳細を閲覧者別 owner 表示で取得する。
     *
     * @param viewerUserId 認証済み閲覧者 ID（未認証は null）
     */
    public MarketListingResponse getListing(Long id, String lang, Long viewerUserId) {
        RecruitmentListingEntity entity = viewerUserId == null
                ? listingRepository.findPublicMarketListingById(id)
                        .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND))
                : listingRepository.findAccessibleMarketListingById(
                        id, accessibleListingIdsOrSentinel(viewerUserId))
                        .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND));
        // 単一札でも一覧と同じ解決経路（訳→日本語fallback込み）を通して表記を揃える。
        MarketResolverMaps maps = buildResolverMaps(List.of(entity), lang, viewerUserId);
        return toMarketListingResponse(entity, maps);
    }

    private Set<Long> accessibleListingIdsOrSentinel(Long viewerUserId) {
        Set<Long> listingIds = new LinkedHashSet<>(
                listingVisibilityResolver.findAccessibleSelectedListingIds(viewerUserId));
        if (listingIds.isEmpty()) {
            listingIds.add(-1L);
        }
        return listingIds;
    }

    // =====================================================================
    // §3.3 地域ファサード
    // =====================================================================

    /**
     * 地域ファサード（日本語名）。後方互換のため lang 未指定オーバーロードを残す。
     *
     * @param prefecture 都道府県コード（null=都道府県一覧）
     * @return 地域ノードリスト（マスタ日本語名）
     */
    public List<MarketRegionNodeResponse> getRegions(String prefecture) {
        return getRegions(prefecture, null);
    }

    /**
     * 地域ファサード（多言語対応）。{@code prefecture} 未指定なら都道府県一覧、指定なら配下市区町村一覧。
     *
     * <p>{@code lang} が訳テーブル対象言語なら {@code region_translations} の訳名で、無ければ
     * マスタの日本語名で {@code name} を返す（fallback）。{@code lang} 未指定/ja は従来どおり日本語名。</p>
     *
     * @param prefecture 都道府県コード（null=都道府県一覧）
     * @param lang       正規化済み言語コード（null=日本語マスタ名）
     * @return 地域ノードリスト（name は解決済み・camelCase は Jackson が担保）
     */
    public List<MarketRegionNodeResponse> getRegions(String prefecture, String lang) {
        String normalizedPref = blankToNull(prefecture);
        if (normalizedPref == null) {
            List<PrefectureEntity> prefs = prefectureRepository.findAllByOrderByCodeAsc();
            Map<String, String> tr = regionTranslationService.resolveNames(
                    prefs.stream().map(PrefectureEntity::getCode).toList(), lang);
            return prefs.stream()
                    .map(p -> new MarketRegionNodeResponse(
                            p.getCode(), tr.getOrDefault(p.getCode(), p.getName()), null))
                    .toList();
        }
        List<CityEntity> cities = cityRepository.findByPrefectureCodeOrderByCodeAsc(normalizedPref);
        Map<String, String> tr = regionTranslationService.resolveNames(
                cities.stream().map(CityEntity::getCode).toList(), lang);
        return cities.stream()
                .map(c -> new MarketRegionNodeResponse(
                        c.getCode(), tr.getOrDefault(c.getCode(), c.getName()), c.getPrefectureCode()))
                .toList();
    }

    // =====================================================================
    // §3.4 件数集計
    // =====================================================================

    /**
     * 地域別の立っている札件数を返す（日本語名）。後方互換のため lang 未指定オーバーロードを残す。
     *
     * @return 都道府県別・市区町村別の件数サマリ
     */
    public MarketSummaryResponse getSummary() {
        return getSummary(null);
    }

    /**
     * 地域別の立っている札件数を返す（多言語対応・パンくず/集客用・PII なし）。
     *
     * <p>地域名は {@code lang} の訳があれば訳名、無ければマスタ日本語名（fallback）。</p>
     *
     * @param lang 正規化済み言語コード（null=日本語マスタ名）
     * @return 都道府県別・市区町村別の件数サマリ（name は解決済み）
     */
    public MarketSummaryResponse getSummary(String lang) {
        // 都道府県名のマスタ名解決用マップ
        Map<String, String> prefNames = new LinkedHashMap<>();
        for (PrefectureEntity p : prefectureRepository.findAllByOrderByCodeAsc()) {
            prefNames.put(p.getCode(), p.getName());
        }

        List<Object[]> prefRows = listingRepository.countMarketListingsByPrefecture();
        Map<String, String> prefTr = regionTranslationService.resolveNames(
                prefRows.stream().map(r -> (String) r[0]).toList(), lang);
        List<MarketSummaryResponse.RegionCount> byPrefecture = new ArrayList<>();
        for (Object[] row : prefRows) {
            String code = (String) row[0];
            long count = ((Number) row[1]).longValue();
            // 訳 → マスタ日本語名 → コードの順でフォールバック。
            String name = prefTr.getOrDefault(code, prefNames.getOrDefault(code, code));
            byPrefecture.add(new MarketSummaryResponse.RegionCount(code, name, count));
        }

        List<Object[]> cityRows = listingRepository.countMarketListingsByCity();
        Map<String, String> cityNames = resolveCityNames(cityRows);
        Map<String, String> cityTr = regionTranslationService.resolveNames(
                cityRows.stream().map(r -> (String) r[0]).toList(), lang);
        List<MarketSummaryResponse.RegionCount> byCity = new ArrayList<>();
        for (Object[] row : cityRows) {
            String code = (String) row[0];
            long count = ((Number) row[1]).longValue();
            String name = cityTr.getOrDefault(code, cityNames.getOrDefault(code, code));
            byCity.add(new MarketSummaryResponse.RegionCount(code, name, count));
        }

        return new MarketSummaryResponse(byPrefecture, byCity);
    }

    // =====================================================================
    // 内部ヘルパー
    // =====================================================================

    private String categoryNameKey(RecruitmentCategoryEntity c) {
        return c.getNameI18nKey();
    }

    private Map<String, String> resolveCityNames(List<Object[]> cityRows) {
        List<String> codes = cityRows.stream().map(r -> (String) r[0]).toList();
        Map<String, String> names = new LinkedHashMap<>();
        for (CityEntity c : cityRepository.findAllById(codes)) {
            names.put(c.getCode(), c.getName());
        }
        return names;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
