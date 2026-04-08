package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentCategoryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11 募集型予約: カテゴリマスタ Controller (§9.7)。
 */
@RestController
@RequestMapping("/api/v1/recruitment-categories")
@Tag(name = "F03.11 募集型予約 / カテゴリ", description = "募集の固定カテゴリマスタ取得")
@RequiredArgsConstructor
public class RecruitmentCategoryController {

    private final RecruitmentCategoryService categoryService;

    @GetMapping
    @Operation(summary = "全カテゴリ取得", description = "i18n キー込みで全アクティブカテゴリを表示順で返す")
    public ResponseEntity<ApiResponse<List<RecruitmentCategoryResponse>>> listCategories() {
        return ResponseEntity.ok(ApiResponse.of(categoryService.listCategories()));
    }
}
