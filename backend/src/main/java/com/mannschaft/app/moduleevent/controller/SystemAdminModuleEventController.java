package com.mannschaft.app.moduleevent.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.moduleevent.api.ModuleEventAdminApi;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventQuarantineResponse;
import com.mannschaft.app.moduleevent.api.dto.ResumeModuleEventRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 隔離されたモジュールイベントを一覧・再開するSYSTEM_ADMIN専用HTTP入口。
 * SecurityConfigのrequestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")が強制する。
 */
@RestController
@RequestMapping("/api/v1/system-admin/module-events")
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RequiredArgsConstructor
public class SystemAdminModuleEventController {
    private final ModuleEventAdminApi api;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "隔離した基盤イベントを機能の有効状態に依存せずSYSTEM_ADMINが確認するため")
    public PagedResponse<ModuleEventQuarantineResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return api.list(status, page, size);
    }

    @PostMapping("/{publicationId}/resume")
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "隔離した基盤イベントを機能の有効状態に依存せずSYSTEM_ADMINが理由付きで再開するため")
    public ResponseEntity<Void> resume(@PathVariable UUID publicationId,
            @Valid @RequestBody ResumeModuleEventRequest request) {
        api.resume(publicationId, request.reason());
        return ResponseEntity.noContent().build();
    }
}
