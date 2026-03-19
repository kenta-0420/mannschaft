package com.mannschaft.app.template;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.UpdateLevelAvailabilityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM_ADMIN向けモジュール管理コントローラー。モジュール一覧・レベル別利用可否更新を提供する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/modules")
@Tag(name = "システム管理 - モジュール")
@RequiredArgsConstructor
public class SystemAdminModuleController {

    private final ModuleService moduleService;
    private final SystemAdminTemplateService systemAdminTemplateService;

    /**
     * 全モジュール一覧を取得する（SYSTEM_ADMIN用）。
     */
    @GetMapping
    @Operation(summary = "全モジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModuleResponse>>> getAllModules() {
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

    /**
     * モジュールのレベル別利用可否を更新する。
     */
    @PatchMapping("/{id}/level-availability")
    @Operation(summary = "レベル別利用可否更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Void> updateLevelAvailability(
            @PathVariable Long id,
            @Valid @RequestBody UpdateLevelAvailabilityRequest request) {
        systemAdminTemplateService.updateLevelAvailability(id, request);
        return ResponseEntity.ok().build();
    }
}
