package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 当番ローテーション作成・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class DutyRotationRequest {

    @NotBlank(message = "当番名を入力してください")
    @Size(max = 100, message = "当番名は100文字以内で入力してください")
    private final String dutyName;

    /** ローテーション種別（DAILY / WEEKLY。デフォルト: DAILY） */
    private final String rotationType;

    @NotEmpty(message = "メンバーを1人以上指定してください")
    private final List<Long> memberOrder;

    @NotNull(message = "開始日を指定してください")
    private final LocalDate startDate;

    @Size(max = 10, message = "アイコンは10文字以内で入力してください")
    private final String icon;

    private final Boolean isEnabled;
}
