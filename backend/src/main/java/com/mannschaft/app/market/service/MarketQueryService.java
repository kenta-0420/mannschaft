package com.mannschaft.app.market.service;

import com.mannschaft.app.common.BusinessException;
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
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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
    private final RecruitmentCategoryRepository categoryRepository;
    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

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
        String normalizedPref = blankToNull(prefecture);
        String normalizedCity = blankToNull(city);
        String normalizedKeyword = blankToNull(keyword);

        Page<RecruitmentListingEntity> page = listingRepository.searchMarketListings(
                normalizedPref, normalizedCity, categoryId, normalizedKeyword,
                includeRegionNone, pageable);

        // N+1 回避: ページ内の全札からカテゴリ/scope/地域コードを集約し、
        // それぞれ findAllById で 1 SQL ずつバルク解決して Map 引きでマッピングする。
        List<RecruitmentListingEntity> listings = page.getContent();
        MarketResolverMaps maps = buildResolverMaps(listings);
        return page.map(e -> toMarketListingResponse(e, maps));
    }

    /** ページ内の全札に必要なマスタを一括取得した参照 Map 群（N+1 回避）。 */
    private record MarketResolverMaps(
            Map<Long, RecruitmentCategoryEntity> categories,
            Map<Long, TeamEntity> teams,
            Map<Long, OrganizationEntity> organizations,
            Map<String, PrefectureEntity> prefectures,
            Map<String, CityEntity> cities) {
    }

    private MarketResolverMaps buildResolverMaps(List<RecruitmentListingEntity> listings) {
        Set<Long> categoryIds = new LinkedHashSet<>();
        Set<Long> teamIds = new LinkedHashSet<>();
        Set<Long> orgIds = new LinkedHashSet<>();
        Set<String> prefCodes = new LinkedHashSet<>();
        Set<String> cityCodes = new LinkedHashSet<>();

        for (RecruitmentListingEntity e : listings) {
            if (e.getCategoryId() != null) {
                categoryIds.add(e.getCategoryId());
            }
            if (e.getScopeType() == RecruitmentScopeType.TEAM) {
                if (e.getScopeId() != null) {
                    teamIds.add(e.getScopeId());
                }
            } else if (e.getScopeId() != null) {
                orgIds.add(e.getScopeId());
            }
            if (e.getPrefectureCode() != null) {
                prefCodes.add(e.getPrefectureCode());
            }
            if (e.getCityCode() != null) {
                cityCodes.add(e.getCityCode());
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
        Map<String, PrefectureEntity> prefectures = new LinkedHashMap<>();
        for (PrefectureEntity p : prefectureRepository.findAllById(prefCodes)) {
            prefectures.put(p.getCode(), p);
        }
        Map<String, CityEntity> cities = new LinkedHashMap<>();
        for (CityEntity c : cityRepository.findAllById(cityCodes)) {
            cities.put(c.getCode(), c);
        }
        return new MarketResolverMaps(categories, teams, organizations, prefectures, cities);
    }

    private MarketListingResponse toMarketListingResponse(
            RecruitmentListingEntity e, MarketResolverMaps maps) {
        return new MarketListingResponse(
                e.getId(),
                e.getTitle(),
                resolveCategoryFromMap(e.getCategoryId(), maps),
                resolveOwnerFromMap(e.getScopeType(), e.getScopeId(), maps),
                resolveRegionFromMap(e.getPrefectureCode(), e.getCityCode(), maps),
                e.getLocation(),
                e.getStartAt(),
                e.getApplicationDeadline(),
                e.getCapacity(),
                e.getConfirmedCount(),
                e.getStatus().name(),
                // Phase 1 では決済は常に false（謝礼決済は Phase 2）。
                Boolean.FALSE);
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
            RecruitmentScopeType scopeType, Long scopeId, MarketResolverMaps maps) {
        if (scopeType == RecruitmentScopeType.TEAM) {
            TeamEntity t = maps.teams().get(scopeId);
            return t == null
                    ? new MarketOwnerDto("TEAM", scopeId, null, null)
                    : new MarketOwnerDto("TEAM", scopeId, t.getName(), t.getIconUrl());
        }
        OrganizationEntity o = maps.organizations().get(scopeId);
        return o == null
                ? new MarketOwnerDto("ORGANIZATION", scopeId, null, null)
                : new MarketOwnerDto("ORGANIZATION", scopeId, o.getName(), o.getIconUrl());
    }

    private MarketRegionDto resolveRegionFromMap(
            String prefectureCode, String cityCode, MarketResolverMaps maps) {
        if (prefectureCode == null && cityCode == null) {
            return null;
        }
        String prefName = prefectureCode == null ? null
                : maps.prefectures().containsKey(prefectureCode)
                        ? maps.prefectures().get(prefectureCode).getName() : null;
        String cityName = cityCode == null ? null
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
        RecruitmentListingEntity entity = listingRepository.findPublicMarketListingById(id)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.LISTING_NOT_FOUND));
        return toMarketListingResponse(entity);
    }

    // =====================================================================
    // §3.3 地域ファサード
    // =====================================================================

    /**
     * 地域ファサード。{@code prefecture} 未指定なら都道府県一覧、指定なら配下市区町村一覧を返す。
     *
     * @param prefecture 都道府県コード（null=都道府県一覧）
     * @return 地域ノードリスト
     */
    public List<MarketRegionNodeResponse> getRegions(String prefecture) {
        String normalizedPref = blankToNull(prefecture);
        if (normalizedPref == null) {
            return prefectureRepository.findAllByOrderByCodeAsc().stream()
                    .map(p -> new MarketRegionNodeResponse(p.getCode(), p.getName(), null))
                    .toList();
        }
        return cityRepository.findByPrefectureCodeOrderByCodeAsc(normalizedPref).stream()
                .map(c -> new MarketRegionNodeResponse(c.getCode(), c.getName(), c.getPrefectureCode()))
                .toList();
    }

    // =====================================================================
    // §3.4 件数集計
    // =====================================================================

    /**
     * 地域別の立っている札件数を返す（パンくず/集客用・PII なし）。
     *
     * @return 都道府県別・市区町村別の件数サマリ
     */
    public MarketSummaryResponse getSummary() {
        // 都道府県名のマスタ名解決用マップ
        Map<String, String> prefNames = new LinkedHashMap<>();
        for (PrefectureEntity p : prefectureRepository.findAllByOrderByCodeAsc()) {
            prefNames.put(p.getCode(), p.getName());
        }

        List<MarketSummaryResponse.RegionCount> byPrefecture = new ArrayList<>();
        for (Object[] row : listingRepository.countMarketListingsByPrefecture()) {
            String code = (String) row[0];
            long count = ((Number) row[1]).longValue();
            byPrefecture.add(new MarketSummaryResponse.RegionCount(
                    code, prefNames.getOrDefault(code, code), count));
        }

        List<Object[]> cityRows = listingRepository.countMarketListingsByCity();
        Map<String, String> cityNames = resolveCityNames(cityRows);
        List<MarketSummaryResponse.RegionCount> byCity = new ArrayList<>();
        for (Object[] row : cityRows) {
            String code = (String) row[0];
            long count = ((Number) row[1]).longValue();
            byCity.add(new MarketSummaryResponse.RegionCount(
                    code, cityNames.getOrDefault(code, code), count));
        }

        return new MarketSummaryResponse(byPrefecture, byCity);
    }

    // =====================================================================
    // 内部ヘルパー
    // =====================================================================

    private MarketListingResponse toMarketListingResponse(RecruitmentListingEntity e) {
        return new MarketListingResponse(
                e.getId(),
                e.getTitle(),
                resolveCategory(e.getCategoryId()),
                resolveOwner(e.getScopeType(), e.getScopeId()),
                resolveRegion(e.getPrefectureCode(), e.getCityCode()),
                e.getLocation(),
                e.getStartAt(),
                e.getApplicationDeadline(),
                e.getCapacity(),
                e.getConfirmedCount(),
                e.getStatus().name(),
                // Phase 1 では決済は常に false（謝礼決済は Phase 2）。
                Boolean.FALSE);
    }

    private MarketCategoryDto resolveCategory(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .map(c -> new MarketCategoryDto(c.getId(), categoryNameKey(c)))
                .orElse(new MarketCategoryDto(categoryId, null));
    }

    private String categoryNameKey(RecruitmentCategoryEntity c) {
        return c.getNameI18nKey();
    }

    private MarketOwnerDto resolveOwner(RecruitmentScopeType scopeType, Long scopeId) {
        if (scopeType == RecruitmentScopeType.TEAM) {
            return teamRepository.findById(scopeId)
                    .map(t -> new MarketOwnerDto("TEAM", scopeId, t.getName(), t.getIconUrl()))
                    .orElse(new MarketOwnerDto("TEAM", scopeId, null, null));
        }
        return organizationRepository.findById(scopeId)
                .map(o -> new MarketOwnerDto("ORGANIZATION", scopeId, o.getName(), o.getIconUrl()))
                .orElse(new MarketOwnerDto("ORGANIZATION", scopeId, null, null));
    }

    private MarketRegionDto resolveRegion(String prefectureCode, String cityCode) {
        if (prefectureCode == null && cityCode == null) {
            return null;
        }
        String prefName = prefectureCode == null ? null
                : prefectureRepository.findById(prefectureCode)
                        .map(PrefectureEntity::getName).orElse(null);
        String cityName = cityCode == null ? null
                : cityRepository.findById(cityCode)
                        .map(CityEntity::getName).orElse(null);
        return new MarketRegionDto(prefectureCode, prefName, cityCode, cityName);
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
