package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 記念日登録・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AnniversaryRequest {

    @NotBlank(message = "記念日名を入力してください")
    @Size(max = 200, message = "記念日名は200文字以内で入力してください")
    private final String name;

    @NotNull(message = "日付を指定してください")
    private final LocalDate date;

    private final Boolean repeatAnnually;

    private final Integer notifyDaysBefore;
}
