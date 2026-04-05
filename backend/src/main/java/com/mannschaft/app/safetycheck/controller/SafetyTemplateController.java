package com.mannschaft.app.safetycheck.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.safetycheck.dto.CreateTemplateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.dto.UpdateTemplateRequest;
import com.mannschaft.app.safetycheck.service.SafetyTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 安否確認テンプレートコントローラー。テンプレートの取得・作成・更新APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/safety-checks/templates")
@Tag(name = "安否確認テンプレート管理", description = "F03.6 安否確認テンプレート")
@RequiredArgsConstructor
public class SafetyTemplateController {

    private final SafetyTemplateService templateService;


    /**
     * 利用可能なテンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SafetyTemplateResponse>>> listTemplates(
            @RequestParam String scopeType,
            @RequestParam Long scopeId) {
        List<SafetyTemplateResponse> templates = templateService.listTemplates(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(templates));
    }

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/{templateId}")
    @Operation(summary = "テンプレート詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SafetyTemplateResponse>> getTemplate(
            @PathVariable Long templateId) {
        SafetyTemplateResponse response = templateService.getTemplate(templateId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SafetyTemplateResponse>> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {
        SafetyTemplateResponse response = templateService.createTemplate(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PatchMapping("/{templateId}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SafetyTemplateResponse>> updateTemplate(
            @PathVariable Long templateId,
            @Valid @RequestBody UpdateTemplateRequest request) {
        SafetyTemplateResponse response = templateService.updateTemplate(templateId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
