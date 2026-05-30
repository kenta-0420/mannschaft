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

    /**
     * レスポンスに地域・フレンド宛先を付与する。
     *
     * @param base   MapStruct が生成した基本レスポンス（region/friendTargets は null）
     * @param entity 札エンティティ
     * @return enrich 済みレスポンス
     */
    public RecruitmentListingResponse enrich(RecruitmentListingResponse base, RecruitmentListingEntity entity) {
        RecruitmentRegionView region = buildRegion(entity.getPrefectureCode(), entity.getCityCode());
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
                friendTargets);
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
