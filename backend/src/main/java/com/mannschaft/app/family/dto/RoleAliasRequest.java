package com.mannschaft.app.family.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ロール呼称一括設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RoleAliasRequest {

    @NotEmpty(message = "エイリアス設定は1件以上指定してください")
    @Valid
    private final List<AliasEntry> aliases;

    /**
     * 個別のエイリアス設定。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AliasEntry {

        @jakarta.validation.constraints.NotBlank(message = "ロール名は必須です")
        private final String roleName;

        @jakarta.validation.constraints.NotNull(message = "表示名は必須です")
        @jakarta.validation.constraints.Size(max = 50, message = "表示名は50文字以内で入力してください")
        private final String displayAlias;
    }
}
