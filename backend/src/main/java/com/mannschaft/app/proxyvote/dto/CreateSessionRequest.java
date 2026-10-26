package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投票セッション作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSessionRequest {

    @NotBlank
    private final String scopeType;

    private final Long teamId;

    private final Long organizationId;

    @NotBlank
    private final String resolutionMode;

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    private final LocalDate meetingDate;

    private final LocalDateTime votingStartAt;

    private final LocalDateTime votingEndAt;

    private final Boolean isAnonymous;

    private final String quorumType;

    private final BigDecimal quorumThreshold;

    private final Boolean isAutoAcceptDelegation;

    private final String remindBeforeHours;

    @Valid
    private final List<MotionRequest> motions;
}
