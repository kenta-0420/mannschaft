package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VillageCategoryRequest(
        @NotBlank @Size(max = 64) String name,
        String parentId,
        Integer displayOrder
) {}
