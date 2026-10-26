package com.mannschaft.app.schedule.dto;

import lombok.Getter;

/**
 * 行事カテゴリレスポンスDTO。
 */
@Getter
public class EventCategoryResponse {

    private final Long id;

    private final String name;

    private final String color;

    private final String icon;

    private final Boolean isDayOffCategory;

    private final Integer sortOrder;

    /** スコープ: "TEAM" または "ORGANIZATION" */
    private final String scope;

    /**
     * 由来の組織ID（F01.2.1 §9.2 #9・#10）。チームが複数の親組織に加盟しているとき、
     * 組織スコープのカテゴリがどの親組織のものかを示す。チーム固有・組織自身の一覧などで
     * 由来を区別する必要が無い場合は null。
     */
    private final Long sourceOrganizationId;

    /** 由来の組織IDを持たない（チーム固有・組織自身の一覧用）。 */
    public EventCategoryResponse(Long id, String name, String color, String icon,
                                 Boolean isDayOffCategory, Integer sortOrder, String scope) {
        this(id, name, color, icon, isDayOffCategory, sortOrder, scope, null);
    }

    public EventCategoryResponse(Long id, String name, String color, String icon,
                                 Boolean isDayOffCategory, Integer sortOrder, String scope,
                                 Long sourceOrganizationId) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.icon = icon;
        this.isDayOffCategory = isDayOffCategory;
        this.sortOrder = sortOrder;
        this.scope = scope;
        this.sourceOrganizationId = sourceOrganizationId;
    }
}
