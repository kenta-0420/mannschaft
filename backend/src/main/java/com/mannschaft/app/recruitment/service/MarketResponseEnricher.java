package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.matching.entity.CityEntity;
import com.mannschaft.app.matching.entity.PrefectureEntity;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.FriendTargetView;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentRegionView;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F22.1 市: {@link RecruitmentListingResponse} に地域情報（マスタ名込み）とフレンド宛先を
 * enrich するヘルパー（02_api_design §4 レスポンス）。
 *
 * <p>MapStruct では {@code region}/{@code friendTargets} を ignore しているため、本クラスで
 * {@code prefectures}/{@code cities} マスタ名を引き、フレンド宛先（{@code FRIEND_TEAMS_ONLY} のみ）を
 * 付与した新インスタンスを生成する。札主 ADMIN 向けレスポンス（作成・編集）で使う。</p>
 */
@Component
@RequiredArgsConstructor
public class MarketResponseEnricher {

    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;
    private final MarketFriendTargetService marketFriendTargetService;
    // F22.1 市 Phase2 D: 複数地域募集（N:N）の中間表（既存メソッド再利用・新規 @Query なし）
    private final RecruitmentListingRegionRepository listingRegionRepository;

    /**
     * レスポンスに地域・フレンド宛先を付与する。
     *
     * @param base   MapStruct が生成した基本レスポンス（region/friendTargets は null）
     * @param entity 札エンティティ
     * @return enrich 済みレスポンス
     */
    public RecruitmentListingResponse enrich(RecruitmentListingResponse base, RecruitmentListingEntity entity) {
        // F22.1 Phase2 D: 中間表（N:N）から全地域を解決（既存 findByListingIdOrderByIdAsc を再利用）。
        List<RecruitmentRegionView> regions = listingRegionRepository
                .findByListingIdOrderByIdAsc(entity.getId()).stream()
                .map(r -> buildRegion(r.getPrefectureCode(), r.getCityCode()))
                .filter(java.util.Objects::nonNull)
                .toList();

        // 代表地域: 中間表先頭を優先。中間表が空（旧データ・地域なし）の場合は旧単一列で後方互換。
        RecruitmentRegionView region = regions.isEmpty()
                ? buildRegion(entity.getPrefectureCode(), entity.getCityCode())
                : regions.get(0);

        List<FriendTargetView> friendTargets =
                entity.getVisibility() == RecruitmentVisibility.FRIEND_TEAMS_ONLY
                        ? marketFriendTargetService.getTargetViews(entity.getId())
                        : List.of();

        return new RecruitmentListingResponse(
                base.getId(),
                base.getScopeType(),
                base.getScopeId(),
                base.getCategoryId(),
                base.getCategoryNameI18nKey(),
                base.getSubcategoryId(),
                base.getSubcategoryName(),
                base.getTitle(),
                base.getDescription(),
                base.getParticipationType(),
                base.getStartAt(),
                base.getEndAt(),
                base.getApplicationDeadline(),
                base.getAutoCancelAt(),
                base.getCapacity(),
                base.getMinCapacity(),
                base.getConfirmedCount(),
                base.getWaitlistCount(),
                base.getWaitlistMax(),
                base.getPaymentEnabled(),
                base.getPrice(),
                base.getVisibility(),
                base.getStatus(),
                base.getLocation(),
                base.getReservationLineId(),
                base.getImageUrl(),
                base.getCancellationPolicyId(),
                base.getCreatedBy(),
                base.getCancelledAt(),
                base.getCancelledBy(),
                base.getCancelledReason(),
                base.getCreatedAt(),
                base.getUpdatedAt(),
                entity.getPrefectureCode(),
                entity.getCityCode(),
                region,
                regions,
                friendTargets,
                // F22.1 謝礼決済: 受領主体（編集フォーム表示用）。entity を正準として保持する。
                entity.getPayeeKind(),
                entity.getPayeeUserId());
    }

    /**
     * 地域コードからマスタ名込みのビューを構築する。両方 null なら null を返す。
     *
     * @param prefectureCode 都道府県コード（null 可）
     * @param cityCode       市区町村コード（null 可）
     * @return 地域ビュー（地域なしは null）
     */
    public RecruitmentRegionView buildRegion(String prefectureCode, String cityCode) {
        if (prefectureCode == null && cityCode == null) {
            return null;
        }
        String prefectureName = prefectureCode == null ? null
                : prefectureRepository.findById(prefectureCode)
                        .map(PrefectureEntity::getName).orElse(null);
        String cityName = cityCode == null ? null
                : cityRepository.findById(cityCode)
                        .map(CityEntity::getName).orElse(null);
        return new RecruitmentRegionView(prefectureCode, prefectureName, cityCode, cityName);
    }
}
