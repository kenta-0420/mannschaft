package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 節作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateMatchdayRequest {

    @NotBlank @Size(max = 100)
    private final String name;

    private final Integer matchdayNumber;
    private final LocalDate scheduledDate;
}
