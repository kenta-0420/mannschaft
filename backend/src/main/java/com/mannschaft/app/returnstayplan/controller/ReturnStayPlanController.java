package com.mannschaft.app.returnstayplan.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.returnstayplan.dto.OwnPlan;
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
    public ResponseEntity<PagedResponse<OwnPlan>> list(
            @RequestParam(defaultValue = "false") boolean includeEnded,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = service.list(SecurityUtils.getCurrentUserId(), includeEnded, page, size);
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(
                        result.getTotalElements(), page, size, result.getTotalPages())));
    }

    /**
     * 計画の所有者は認証主体に固定するが、本文の公開先 {@code teamIds} をスコープIDとして受け取るため
     * 自己スコープ（到達不能）の主張はできない。認可の実体は Service にある:
     * {@code ReturnStayPlanService#validateTeamIds} が {@code countSaveableTeams}（本人が MEMBER として在籍し、
     * アーカイブ・削除されていないチーム）の件数と指定件数を比べ、1件でも外れれば {@code TEAM_ACCESS_DENIED} で
     * 保存前に拒否する。固定する契約テスト: {@code ReturnStayPlanPersistenceIT}（公開先チームの所属検証）。
     */
    @PostMapping
    @AuthorizedInService
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201", description = "Created")
    public ResponseEntity<ApiResponse<OwnPlan>> create(
            @Valid @RequestBody ReturnStayPlanCreateRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.of(
                service.create(SecurityUtils.getCurrentUserId(), request)));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<ApiResponse<OwnPlan>> get(@PathVariable UUID planId) {
        return ResponseEntity.ok(ApiResponse.of(service.getForOwner(SecurityUtils.getCurrentUserId(), planId)));
    }

    @PutMapping("/{planId}")
    public ResponseEntity<ApiResponse<OwnPlan>> update(
            @PathVariable UUID planId,
            @RequestParam Long version,
            @Valid @RequestBody ReturnStayPlanCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.of(service.update(
                SecurityUtils.getCurrentUserId(), planId, version, request)));
    }

    @DeleteMapping("/{planId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204", description = "No Content")
    public ResponseEntity<Void> delete(@PathVariable UUID planId) {
        service.delete(SecurityUtils.getCurrentUserId(), planId);
        return ResponseEntity.noContent().build();
    }
}
