package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TODO カスタムステータスラベル作成リクエスト DTO（F02.3.1）。
 */
@Getter
@RequiredArgsConstructor
public class CreateTodoStatusLabelRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    /** OPEN / IN_PROGRESS / COMPLETED のいずれか。 */
    @NotBlank
    @Pattern(regexp = "OPEN|IN_PROGRESS|COMPLETED",
            message = "bucket は OPEN / IN_PROGRESS / COMPLETED のいずれかである必要があります")
    private final String bucket;

    /** #RRGGBB 形式（任意）。 */
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
    private final String color;

    @Min(0)
    private final Integer sortOrder;
}
