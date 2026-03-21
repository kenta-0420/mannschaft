package com.mannschaft.app.member.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.member.dto.CreateSectionRequest;
import com.mannschaft.app.member.dto.SectionResponse;
import com.mannschaft.app.member.dto.UpdateSectionRequest;
import com.mannschaft.app.member.service.TeamPageSectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ページセクションコントローラー。セクションのCRUD APIを提供する。
 */
@RestController
@Tag(name = "ページセクション", description = "F06.2 ページセクションCRUD")
@RequiredArgsConstructor
public class TeamPageSectionController {

    private final TeamPageSectionService sectionService;

    /**
     * セクション一覧を取得する。
     */
    @GetMapping("/api/v1/team/pages/{pageId}/sections")
    @Operation(summary = "セクション一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SectionResponse>>> listSections(@PathVariable Long pageId) {
        List<SectionResponse> response = sectionService.listSections(pageId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * セクションを追加する。
     */
    @PostMapping("/api/v1/team/pages/{pageId}/sections")
    @Operation(summary = "セクション追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SectionResponse>> createSection(
            @PathVariable Long pageId,
            @Valid @RequestBody CreateSectionRequest request) {
        SectionResponse response = sectionService.createSection(pageId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * セクションを更新する。
     */
    @PutMapping("/api/v1/team/sections/{id}")
    @Operation(summary = "セクション更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SectionResponse>> updateSection(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSectionRequest request) {
        SectionResponse response = sectionService.updateSection(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * セクションを削除する。
     */
    @DeleteMapping("/api/v1/team/sections/{id}")
    @Operation(summary = "セクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSection(@PathVariable Long id) {
        sectionService.deleteSection(id);
        return ResponseEntity.noContent().build();
    }
}
