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
}
