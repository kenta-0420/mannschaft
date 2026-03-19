package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.template.dto.ModuleSummaryResponse;
import com.mannschaft.app.template.dto.TemplateResponse;
import com.mannschaft.app.template.dto.TemplateSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * テンプレート参照コントローラー。テンプレート一覧・詳細・紐付きモジュール取得を提供する。
 */
@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "テンプレート管理")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /**
     * アクティブなテンプレート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "テンプレート一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TemplateSummaryResponse>>> getTemplates() {
        return ResponseEntity.ok(ApiResponse.of(templateService.getTemplates()));
    }

    /**
     * テンプレート詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "テンプレート詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TemplateResponse>> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    /**
     * テンプレートに紐付くモジュール一覧を取得する。
     */
    @GetMapping("/{id}/modules")
    @Operation(summary = "テンプレートモジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModuleSummaryResponse>>> getTemplateModules(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(templateService.getTemplateModules(id)));
    }
}
