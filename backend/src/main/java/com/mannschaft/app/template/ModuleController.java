package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
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
 * モジュールカタログコントローラー。選択式モジュールの参照を提供する。
 */
@RestController
@RequestMapping("/api/v1/modules")
@Tag(name = "モジュール管理")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleService moduleService;

    /**
     * 選択式モジュールカタログを取得する。
     */
    @GetMapping
    @Operation(summary = "モジュールカタログ取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModuleResponse>>> getModuleCatalog() {
        return ResponseEntity.ok(ApiResponse.of(moduleService.getModuleCatalog()));
    }

    /**
     * モジュール詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "モジュール詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ModuleResponse>> getModule(@PathVariable Long id) {
        return ResponseEntity.ok(moduleService.getModule(id));
    }
}
