package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TODO カスタムステータスラベル更新リクエスト DTO（F02.3.1）。
 *
 * <p>PATCH 的に全項目 NULL 可。NULL のフィールドは更新対象外。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateTodoStatusLabelRequest {

    @Size(max = 50)
    private final String name;

    /** OPEN / IN_PROGRESS / COMPLETED のいずれか（NULL 可。NULL のときは更新対象外）。 */
    @Pattern(regexp = "OPEN|IN_PROGRESS|COMPLETED",
            message = "bucket は OPEN / IN_PROGRESS / COMPLETED のいずれかである必要があります")
    private final String bucket;

    /** #RRGGBB 形式。 */
    @Pattern(regexp = "^#[0-9a-fA-F]{6}$")
    private final String color;

    @Min(0)
    private final Integer sortOrder;
}
