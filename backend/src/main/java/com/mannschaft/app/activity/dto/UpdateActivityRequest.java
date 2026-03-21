package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 活動記録更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateActivityRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 10000)
    private final String description;

    @NotNull
    private final LocalDate activityDate;

    @Size(max = 200)
    private final String location;

    private final String visibility;

    @Size(max = 500)
    private final String coverImageUrl;

    private final List<CreateActivityRequest.CustomValueInput> customValues;
}
