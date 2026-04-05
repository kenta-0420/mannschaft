package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * お買い物リスト作成・更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShoppingListRequest {

    @NotBlank(message = "リスト名を入力してください")
    @Size(max = 100, message = "リスト名は100文字以内で入力してください")
    private final String name;

    /** テンプレートリストフラグ（作成時のみ） */
    private final Boolean isTemplate;
}
