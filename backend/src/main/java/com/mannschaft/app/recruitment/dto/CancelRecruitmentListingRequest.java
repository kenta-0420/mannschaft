package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 募集枠の主催者キャンセルリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CancelRecruitmentListingRequest {

    @Size(max = 200)
    private final String reason;
}
