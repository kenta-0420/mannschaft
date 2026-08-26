package com.mannschaft.app.navsettings.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class UpdateNavSettingsRequest {

    @NotNull
    @Size(max = 50)
    private List<String> hiddenNavKeys;

    /**
     * 個人別ナビ表示順（nav_features.key の配列）。任意。
     * null の場合はマスタ sort_order 順にリセットする。
     */
    @Size(max = 100)
    private List<String> navDisplayOrder;
}
