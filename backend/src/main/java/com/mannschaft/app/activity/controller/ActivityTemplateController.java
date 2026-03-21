package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.ImportTemplateRequest;
import com.mannschaft.app.activity.dto.UpdateTemplateRequest;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 活動テンプレートコントローラー。テンプレートのCRUD・共有・インポートAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/activities/templates")
@Tag(name = "活動テンプレート", description = "F06.4 活動テンプレートCRUD・共有・インポート")
@RequiredArgsConstructor
public class ActivityTemplateController {

    private final ActivityTemplateService templateService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * テンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityTemplateResponse>>> listTemplates(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId) {
        return ResponseEntity.ok(ApiResponse.of(templateService.listTemplates(teamId, organizationId)));
    }

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "テンプレート詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityTemplateResponse>> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(templateService.getTemplate(id)));
    }

    /**
     * テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "テンプレート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ActivityTemplateResponse>> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {
        ActivityTemplateResponse response = templateService.createTemplate(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "テンプレート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ActivityTemplateResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(templateService.updateTemplate(id, request)));
    }

    /**
     * テンプレートを削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "テンプレート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 公式テンプレート一覧を取得する。
     */
    @GetMapping("/official")
    @Operation(summary = "公式テンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityTemplateResponse>>> listOfficialTemplates() {
        return ResponseEntity.ok(ApiResponse.of(templateService.listOfficialTemplates()));
    }

    /**
     * テンプレートをインポートする。
     */
    @PostMapping("/import")
    @Operation(summary = "テンプレートインポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "インポート成功")
    public ResponseEntity<ApiResponse<ActivityTemplateResponse>> importTemplate(
            @Valid @RequestBody ImportTemplateRequest request) {
        ActivityTemplateResponse response = templateService.importTemplate(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * テンプレートの共有を有効化する。
     */
    @PostMapping("/{id}/share")
    @Operation(summary = "共有有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "有効化成功")
    public ResponseEntity<ApiResponse<ActivityTemplateResponse>> enableShare(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(templateService.enableShare(id)));
    }

    /**
     * テンプレートの共有を無効化する。
     */
    @DeleteMapping("/{id}/share")
    @Operation(summary = "共有無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> disableShare(@PathVariable Long id) {
        templateService.disableShare(id);
        return ResponseEntity.noContent().build();
    }
}
