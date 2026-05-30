package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集枠の作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateRecruitmentListingRequest {

    @NotNull
    private final Long categoryId;

    private final Long subcategoryId;

    @NotNull
    @Size(max = 100)
    private final String title;

    private final String description;

    @NotNull
    private final RecruitmentParticipationType participationType;

    @NotNull
    private final LocalDateTime startAt;

    @NotNull
    private final LocalDateTime endAt;

    @NotNull
    private final LocalDateTime applicationDeadline;

    @NotNull
    private final LocalDateTime autoCancelAt;

    @NotNull
    @Positive
    private final Integer capacity;

    @NotNull
    @Positive
    private final Integer minCapacity;

    @NotNull
    private final Boolean paymentEnabled;

    private final Integer price;

    @NotNull
    private final RecruitmentVisibility visibility;

    @Size(max = 200)
    private final String location;

    private final Long reservationLineId;

    @Size(max = 500)
    private final String imageUrl;

    private final Long cancellationPolicyId;

    // ===========================================
    // F22.1 市: 地域・フレンド宛先（02_api_design §4）
    // ===========================================

    /**
     * 都道府県コード（JIS X 0401・CHAR(2)）。任意。
     * {@code cityCode} 指定時は整合必須（不整合は {@code MARKET_001}）。
     */
    @Pattern(regexp = "\\d{2}", message = "prefecture_code は 2 桁の数字で指定してください")
    private final String prefectureCode;

    /**
     * 市区町村コード（JIS X 0402・CHAR(5)）。任意。
     * 指定時はマスタ存在＋上位2桁＝prefectureCode を Service で検証（{@code MARKET_001}）。
     */
    @Pattern(regexp = "\\d{5}", message = "city_code は 5 桁の数字で指定してください")
    private final String cityCode;

    /**
     * フレンド宛先（{@code visibility='FRIEND_TEAMS_ONLY'} のとき 1 件以上必須）。
     * 3 粒度（ALL_FRIENDS / FOLDER / TEAM）を混在指定できる。
     */
    @Valid
    private final List<FriendTargetRequest> friendTargets;

    /**
     * 配信対象（既存 F03.11）。{@code FRIEND_TEAMS_ONLY} とは併用不可（{@code MARKET_005}）。
     * 互換のため任意。市の札立て導線では PUBLIC のとき PUBLIC_FEED を指定する。
     */
    private final List<RecruitmentDistributionTargetType> distributionTargets;
}
