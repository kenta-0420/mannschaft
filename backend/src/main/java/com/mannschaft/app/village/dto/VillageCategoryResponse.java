package com.mannschaft.app.village.dto;

import java.util.List;

public record VillageCategoryResponse(
        String id,
        String name,
        String parentId,
        int displayOrder,
        List<VillageCategoryResponse> children
) {}
