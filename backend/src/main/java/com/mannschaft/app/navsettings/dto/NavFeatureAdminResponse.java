package com.mannschaft.app.navsettings.dto;

import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
public class NavFeatureAdminResponse {
    private String key;
    private String labelKey;
    private String icon;
    private String path;
    private boolean fixed;
    private boolean enabled;
    private boolean subscriptionRequired;
    private int sortOrder;
    private boolean mobileVisible;
    private Instant createdAt;
    private Instant updatedAt;

    public static NavFeatureAdminResponse from(NavFeatureEntity entity) {
        return NavFeatureAdminResponse.builder()
                .key(entity.getKey())
                .labelKey(entity.getLabelKey())
                .icon(entity.getIcon())
                .path(entity.getPath())
                .fixed(entity.isFixed())
                .enabled(entity.isEnabled())
                .subscriptionRequired(entity.isSubscriptionRequired())
                .sortOrder(entity.getSortOrder())
                .mobileVisible(entity.isMobileVisible())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
