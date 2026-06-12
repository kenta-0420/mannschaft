package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集枠の詳細レスポンス。
 */
@Getter
@AllArgsConstructor
public class RecruitmentListingResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long categoryId;
    private final String categoryNameI18nKey;
    private final Long subcategoryId;
    private final String subcategoryName;
    private final String title;
    private final String description;
    private final String participationType;
    private final LocalDateTime startAt;
    private final LocalDateTime endAt;
    private final LocalDateTime applicationDeadline;
    private final LocalDateTime autoCancelAt;
    private final Integer capacity;
    private final Integer minCapacity;
    private final Integer confirmedCount;
    private final Integer waitlistCount;
    private final Integer waitlistMax;
    private final Boolean paymentEnabled;
    private final Integer price;
    private final String visibility;
    private final String status;
    private final String location;
    private final Long reservationLineId;
    private final String imageUrl;
    private final Long cancellationPolicyId;
    private final Long createdBy;
    private final LocalDateTime cancelledAt;
    private final Long cancelledBy;
    private final String cancelledReason;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    // ===========================================
    // F22.1 市: 地域・フレンド宛先（02_api_design §4）
    // Service 層で enrich する（MapStruct では ignore）。
    // ===========================================

    private final String prefectureCode;
    private final String cityCode;

    /** 代表地域情報（マスタ名込み・複数地域の先頭）。地域未指定の札では null。 */
    private final RecruitmentRegionView region;

    /**
     * 複数地域募集（N:N・F22.1 Phase2 D）の全地域（マスタ名込み）。
     * 中間表（recruitment_listing_regions）由来。地域を問わない札は空配列。札主の作成/編集応答で返す。
     */
    private final List<RecruitmentRegionView> regions;

    /** フレンド宛先（{@code FRIEND_TEAMS_ONLY} のときのみ非空。札主 ADMIN 向け）。 */
    private final List<FriendTargetView> friendTargets;

    // ===========================================
    // F22.1 市 謝礼決済: 受領主体（編集フォーム表示用・02_api_design §3）
    // entity の payeeKind/payeeUserId と同名のため MapStruct が自動マッピングする。
    // ===========================================

    /** 受領主体種別 {@code USER} / {@code TEAM} / {@code ORG}。決済無効札は null。 */
    private final String payeeKind;

    /** {@code payeeKind=USER} の受領者ユーザー（users.id）。それ以外は null。 */
    private final Long payeeUserId;
}
