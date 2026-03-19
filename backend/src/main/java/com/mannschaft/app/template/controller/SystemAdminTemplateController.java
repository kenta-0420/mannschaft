package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.template.dto.CreateTemplateRequest;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.UpdateTemplateRequest;
import com.mannschaft.app.template.service.SystemAdminTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN向けテンプレート管理コントローラー。テンプレートのCRUDを提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/templates")
@Tag(name = "システム管理 - テンプレート")
@RequiredArgsConstructor
public class SystemAdminTemplateController {

    private final SystemAdminTemplateService systemAdminTemplateService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(systemAdminTemplateService.createTemplate(request, getCurrentUserId()));
    }

    /**
     * テンプレートを更新する。
     */
    @PatchMapping("/{id}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTemplateRequest request) {
        return ResponseEntity.ok(systemAdminTemplateService.updateTemplate(id, request));
    }

    /**
     * テンプレートを論理削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        systemAdminTemplateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }
}
