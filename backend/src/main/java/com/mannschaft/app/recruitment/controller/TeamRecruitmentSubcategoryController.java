package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentSubcategoryRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentSubcategoryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentSubcategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.11 募集型予約: チームのサブカテゴリ管理 Controller (§9.6)。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/recruitment-subcategories")
@Tag(name = "F03.11 募集型予約 / サブカテゴリ", description = "チームのサブカテゴリ CRUD")
@RequiredArgsConstructor
public class TeamRecruitmentSubcategoryController {

    private final RecruitmentSubcategoryService subcategoryService;

    @GetMapping
    @Operation(summary = "サブカテゴリ一覧")
    public ResponseEntity<ApiResponse<List<RecruitmentSubcategoryResponse>>> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(ApiResponse.of(
                subcategoryService.listByScope(RecruitmentScopeType.TEAM, teamId, categoryId)));
    }

    @PostMapping
    @Operation(summary = "サブカテゴリ作成")
    public ResponseEntity<ApiResponse<RecruitmentSubcategoryResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRecruitmentSubcategoryRequest request) {
        RecruitmentSubcategoryResponse response = subcategoryService.create(
                RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PostMapping("/{subcategoryId}/archive")
    @Operation(summary = "サブカテゴリ削除 (論理削除)")
    public ResponseEntity<Void> archive(
            @PathVariable Long teamId,
            @PathVariable Long subcategoryId) {
        subcategoryService.archive(subcategoryId, RecruitmentScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
