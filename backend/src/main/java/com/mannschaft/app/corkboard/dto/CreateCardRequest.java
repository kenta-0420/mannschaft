package com.mannschaft.app.corkboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コルクボードカード作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCardRequest {

    @NotBlank
    @Size(max = 20)
    private final String cardType;

    @Size(max = 30)
    private final String referenceType;

    private final Long referenceId;

    @Size(max = 200)
    private final String title;

    private final String body;

    @Size(max = 2000)
    private final String url;

    @Size(max = 10)
    private final String colorLabel;

    @Size(max = 10)
    private final String cardSize;

    private final Integer positionX;

    private final Integer positionY;

    private final Integer zIndex;

    private final String userNote;

    private final LocalDateTime autoArchiveAt;
}
