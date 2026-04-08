package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F03.11 募集枠の編集リクエスト。
 * §5.7 編集時の制約: participation_type は変更不可のため含まない。
 * 全フィールドオプショナル (PATCH スタイル)。
 */
@Getter
@RequiredArgsConstructor
public class UpdateRecruitmentListingRequest {

    @Size(max = 100)
    private final String title;

    private final String description;

    private final Long subcategoryId;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    private final LocalDateTime applicationDeadline;

    private final LocalDateTime autoCancelAt;

    @Positive
    private final Integer capacity;

    @Positive
    private final Integer minCapacity;

    private final Boolean paymentEnabled;

    private final Integer price;

    private final RecruitmentVisibility visibility;

    @Size(max = 200)
    private final String location;

    private final Long reservationLineId;

    @Size(max = 500)
    private final String imageUrl;

    private final Long cancellationPolicyId;
}
