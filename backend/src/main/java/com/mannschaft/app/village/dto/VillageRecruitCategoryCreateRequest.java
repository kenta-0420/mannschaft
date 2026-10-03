package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 村ごと募集カテゴリの作成リクエスト（F17.1 P2 §6.2 / AC-01・AC-16・AC-17）。
 */
public record VillageRecruitCategoryCreateRequest(
        @NotBlank @Size(max = 40) String name,
        @Size(max = 200) String description,
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
        Integer displayOrder
) {
}
