package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ディビジョン作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateDivisionRequest {

    @NotBlank @Size(max = 100)
    private final String name;

    private final Integer level;
    private final Integer promotionSlots;
    private final Integer relegationSlots;
    private final Integer playoffPromotionSlots;
    private final Integer maxParticipants;
    private final Integer sortOrder;
}
