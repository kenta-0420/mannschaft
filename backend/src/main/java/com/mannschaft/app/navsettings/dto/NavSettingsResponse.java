package com.mannschaft.app.navsettings.dto;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class NavSettingsResponse {
    private List<NavFeatureResponse> features;
}
