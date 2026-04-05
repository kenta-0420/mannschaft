package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 行事カテゴリレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class EventCategoryResponse {

    private final Long id;

    private final String name;

    private final String color;

    private final String icon;

    private final Boolean isDayOffCategory;

    private final Integer sortOrder;

    /** スコープ: "TEAM" または "ORGANIZATION" */
    private final String scope;
}
