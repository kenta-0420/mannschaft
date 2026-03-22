package com.mannschaft.app.directmail.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.directmail.dto.CreateDirectMailTemplateRequest;
import com.mannschaft.app.directmail.dto.DirectMailTemplateResponse;
import com.mannschaft.app.directmail.dto.UpdateDirectMailTemplateRequest;
import com.mannschaft.app.directmail.service.DirectMailTemplateService;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 組織DMテンプレートコントローラー。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/direct-mail-templates")
@Tag(name = "組織DMテンプレート", description = "F09.6 組織DMテンプレートCRUD")
@RequiredArgsConstructor
public class OrganizationDirectMailTemplateController {

    private final DirectMailTemplateService templateService;


    /**
     * テンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "組織DMテンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<DirectMailTemplateResponse>>> listTemplates(
            @PathVariable Long orgId) {
        List<DirectMailTemplateResponse> responses = templateService.listTemplates("ORGANIZATION", orgId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "組織DMテンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<DirectMailTemplateResponse>> createTemplate(
            @PathVariable Long orgId, @Valid @RequestBody CreateDirectMailTemplateRequest request) {
        DirectMailTemplateResponse response = templateService.createTemplate("ORGANIZATION", orgId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "組織DMテンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<DirectMailTemplateResponse>> updateTemplate(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdateDirectMailTemplateRequest request) {
        DirectMailTemplateResponse response = templateService.updateTemplate("ORGANIZATION", orgId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "組織DMテンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long orgId, @PathVariable Long id) {
        templateService.deleteTemplate("ORGANIZATION", orgId, id);
        return ResponseEntity.noContent().build();
    }
}
