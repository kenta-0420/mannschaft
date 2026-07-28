package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.template.dto.ModuleResponse;
import com.mannschaft.app.template.dto.UpdateLevelAvailabilityRequest;
import com.mannschaft.app.template.dto.UpdateModuleActiveRequest;
import com.mannschaft.app.template.dto.UpdateModulePaidPlanRequest;
import com.mannschaft.app.template.service.ModuleService;
import com.mannschaft.app.template.service.SystemAdminTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SYSTEM_ADMIN向けモジュール管理コントローラー。モジュール一覧・レベル別利用可否更新を提供する。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 3 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig
@RestController
@RequestMapping("/api/v1/system-admin/modules")
@Tag(name = "システム管理 - モジュール")
@RequiredArgsConstructor
public class SystemAdminModuleController {

    private final ModuleService moduleService;
    private final SystemAdminTemplateService systemAdminTemplateService;

    /**
     * 全モジュール一覧を取得する（SYSTEM_ADMIN用）。
     *
     * <p>tenant 向けカタログ（OPTIONAL かつ active のみ）ではなく、DEFAULT/OPTIONAL・
     * is_active を問わず全件を返す。無効化したモジュールも一覧に残るため再有効化できる。</p>
     */
    @GetMapping
    @Operation(summary = "全モジュール一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ModuleResponse>>> getAllModules() {
        return ResponseEntity.ok(ApiResponse.of(moduleService.getAllModulesForAdmin()));
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

    /**
     * モジュールの有料プラン要否を更新する。
     * 認可は {@code /api/v1/system-admin/**} のパスベース一括ルール（SYSTEM_ADMIN）で担保する。
     */
    @PatchMapping("/{id}/paid-plan")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "モジュール有料プラン要否更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Void> updatePaidPlan(
            @PathVariable Long id,
            @Valid @RequestBody UpdateModulePaidPlanRequest request) {
        systemAdminTemplateService.updateModulePaidPlan(id, request);
        return ResponseEntity.ok().build();
    }

    /**
     * モジュールの有効/無効を更新する。
     * 認可は {@code /api/v1/system-admin/**} のパスベース一括ルール（SYSTEM_ADMIN）で担保する。
     */
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "モジュール有効/無効更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<Void> updateActive(
            @PathVariable Long id,
            @Valid @RequestBody UpdateModuleActiveRequest request) {
        systemAdminTemplateService.updateModuleActive(id, request);
        return ResponseEntity.ok().build();
    }
}
