package com.mannschaft.app.navsettings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NavFeatureCreateRequest {

    @NotBlank
    @Size(min = 1, max = 50)
    @Pattern(regexp = "^[a-z0-9\\-]+$", message = "小文字英数字とハイフンのみ使用可能です")
    private String key;

    @NotBlank
    @Size(max = 100)
    private String labelKey;

    @NotBlank
    @Size(max = 50)
    private String icon;

    @NotBlank
    @Size(max = 200)
    private String path;

    @NotNull
    private Boolean fixed;

    @NotNull
    private Boolean enabled;

    @NotNull
    private Boolean subscriptionRequired;

    @NotNull
    private Integer sortOrder;

    @NotNull
    private Boolean mobileVisible;
}
