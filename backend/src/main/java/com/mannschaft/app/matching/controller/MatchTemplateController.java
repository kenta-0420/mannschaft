package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.matching.dto.CreateTemplateRequest;
import com.mannschaft.app.matching.dto.TemplateCreateResponse;
import com.mannschaft.app.matching.dto.TemplateResponse;
import com.mannschaft.app.matching.service.MatchTemplateService;
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
 * 募集テンプレートコントローラー。テンプレートのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/matching/templates")
@Tag(name = "募集テンプレート", description = "F08.1 募集テンプレートCRUD")
@RequiredArgsConstructor
public class MatchTemplateController {

    private final MatchTemplateService templateService;

    /**
     * テンプレート一覧。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TemplateResponse>>> listTemplates(@PathVariable Long teamId) {
        List<TemplateResponse> response = templateService.listTemplates(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレート作成。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TemplateCreateResponse>> createTemplate(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTemplateRequest request) {
        TemplateCreateResponse response = templateService.createTemplate(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレート更新。
     */
    @PutMapping("/{id}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody CreateTemplateRequest request) {
        TemplateResponse response = templateService.updateTemplate(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレート削除。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        templateService.deleteTemplate(teamId, id);
        return ResponseEntity.noContent().build();
    }
}
