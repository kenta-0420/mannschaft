package com.mannschaft.app.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 応募作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateProposalRequest {

    private final String message;

    @Valid
    @Size(max = 5)
    private final List<ProposedDateRequest> proposedDates;

    @Size(max = 200)
    private final String proposedVenue;
}
