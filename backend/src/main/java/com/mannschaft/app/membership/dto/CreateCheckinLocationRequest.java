package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チェックイン拠点作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateCheckinLocationRequest {

    @NotBlank(message = "拠点名は必須です")
    @Size(min = 1, max = 100, message = "拠点名は1〜100文字で入力してください")
    private final String name;

    private final Boolean autoCompleteReservation;
}
