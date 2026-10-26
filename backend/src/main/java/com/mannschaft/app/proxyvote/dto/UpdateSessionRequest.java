package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投票セッション更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSessionRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String description;

    private final LocalDateTime votingStartAt;

    private final LocalDateTime votingEndAt;

    private final Boolean isAnonymous;

    private final String quorumType;

    private final BigDecimal quorumThreshold;

    private final Boolean isAutoAcceptDelegation;

    private final String resolutionMode;

    private final LocalDate meetingDate;

    private final String remindBeforeHours;

    @NotNull
    private final Long version;
}
