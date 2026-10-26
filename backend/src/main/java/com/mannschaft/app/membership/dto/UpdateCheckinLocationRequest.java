package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チェックイン拠点更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCheckinLocationRequest {

    @NotBlank(message = "拠点名は必須です")
    @Size(min = 1, max = 100, message = "拠点名は1〜100文字で入力してください")
    private final String name;

    @NotNull(message = "有効フラグは必須です")
    private final Boolean isActive;

    @NotNull(message = "予約自動完了フラグは必須です")
    private final Boolean autoCompleteReservation;
}
