package com.mannschaft.app.returnstayplan.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.service.ReturnStayPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
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
import org.springframework.validation.annotation.Validated;

/** F02.11 HTTP 契約の最小骨格。認証・エラー変換は出陣で実装する。 */
@RestController
@Validated
@RequestMapping("/api/v1/me/return-stay-plans")
public class ReturnStayPlanController {

    private final ReturnStayPlanService service;

    public ReturnStayPlanController(ReturnStayPlanService service) {
        this.service = service;
    }

    @GetMapping
    @SelfScopedEndpoint("ReturnStayPlanController#list は ownerUserId を SecurityUtils からのみ取得する")
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "false") boolean includeEnded,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = service.list(SecurityUtils.getCurrentUserId(), includeEnded, page, size);
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(
                        result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @PostMapping
    @SelfScopedEndpoint("ReturnStayPlanController#create は ownerUserId を SecurityUtils からのみ取得する")
    public ResponseEntity<?> create(@Valid @RequestBody ReturnStayPlanCreateRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.of(
                service.create(SecurityUtils.getCurrentUserId(), request)));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<?> get(@PathVariable UUID planId) {
        return ResponseEntity.ok(ApiResponse.of(service.getForOwner(SecurityUtils.getCurrentUserId(), planId)));
    }

    @PutMapping("/{planId}")
    public ResponseEntity<?> update(
            @PathVariable UUID planId,
            @RequestParam Long version,
            @Valid @RequestBody ReturnStayPlanCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(service.update(
                SecurityUtils.getCurrentUserId(), planId, version, request)));
    }

    @DeleteMapping("/{planId}")
    public ResponseEntity<Void> delete(@PathVariable UUID planId) {
        service.delete(SecurityUtils.getCurrentUserId(), planId);
        return ResponseEntity.noContent().build();
    }
}
