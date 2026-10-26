package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 活動記録複製リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class DuplicateActivityRequest {

    private final LocalDate activityDate;

    @Size(max = 200)
    private final String title;
}
