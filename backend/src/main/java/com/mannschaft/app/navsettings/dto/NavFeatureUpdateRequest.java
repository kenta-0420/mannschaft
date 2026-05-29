package com.mannschaft.app.navsettings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NavFeatureUpdateRequest {

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
