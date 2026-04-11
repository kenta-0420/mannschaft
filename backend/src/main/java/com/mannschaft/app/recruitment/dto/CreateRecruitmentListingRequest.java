package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

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
}
