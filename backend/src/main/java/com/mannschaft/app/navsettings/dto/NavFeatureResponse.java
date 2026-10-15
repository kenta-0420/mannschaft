package com.mannschaft.app.navsettings.dto;

import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NavFeatureResponse {
    private String key;
    private String labelKey;
    private String icon;
    private String path;
    private boolean fixed;
    private int sortOrder;
    private boolean mobileVisible;
    private boolean visible; // ユーザーが表示中かどうか（サーバーサイドで計算）

    public static NavFeatureResponse from(NavFeatureEntity entity, boolean visible) {
        return NavFeatureResponse.builder()
                .key(entity.getKey())
                .labelKey(entity.getLabelKey())
                .icon(entity.getIcon())
                .path(entity.getPath())
                .fixed(entity.isFixed())
                .sortOrder(entity.getSortOrder())
                .mobileVisible(entity.isMobileVisible())
                .visible(visible)
                .build();
    }
}
