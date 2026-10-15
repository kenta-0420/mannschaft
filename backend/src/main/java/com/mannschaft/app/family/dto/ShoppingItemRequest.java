package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * お買い物アイテム追加・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShoppingItemRequest {

    @NotBlank(message = "アイテム名を入力してください")
    @Size(max = 200, message = "アイテム名は200文字以内で入力してください")
    private final String name;

    @Size(max = 50, message = "数量は50文字以内で入力してください")
    private final String quantity;

    @Size(max = 500, message = "メモは500文字以内で入力してください")
    private final String note;

    private final Long assignedTo;

    private final Integer sortOrder;
}
