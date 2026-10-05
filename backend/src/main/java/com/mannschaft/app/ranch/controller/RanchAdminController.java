package com.mannschaft.app.ranch.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationResponse;
import com.mannschaft.app.ranch.dto.RanchCareRuleSummary;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsResponse;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationResponse;
import com.mannschaft.app.ranch.dto.RanchPolicySummary;
import com.mannschaft.app.ranch.service.RanchAdminFacade;
import com.mannschaft.app.ranch.service.RanchSourceAdminFacade;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxHealthSummary;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 管理pathのSYSTEM_ADMIN filterに加え、RanchAdminAdmissionで現在のDB資格とACTIVEを検査する。
 * PrivateSelfAccessGuardは変身を拒否し、bodyから管理主体を受けない。本人ownerは生成しない。
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin/ranch")
@RequiredArgsConstructor
public class RanchAdminController {
    private final RanchAdminFacade facade;
    private final PrivateSelfAccessGuard access;
    private final RanchSourceAdminFacade sourceAdmin;

    @GetMapping("/operational-controls")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが運営設定を照会する管理入口")
    public ResponseEntity<ApiResponse<RanchOperationalControlsResponse>> controls(
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(facade.controls(actorId)));
    }

    @GetMapping("/care-rules")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが公開版の履歴を照会する管理入口")
    public ResponseEntity<CursorPagedResponse<RanchCareRuleSummary>> careRules(
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(facade.careRules(actorId, cursor, limit));
    }

    @PostMapping("/care-rules")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが将来のお世話ルール版を登録する管理入口")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = RanchCareRulePublicationRequest.class)))
    public ResponseEntity<ApiResponse<RanchCareRulePublicationResponse>> publishCare(
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        var result = facade.publishCare(actorId, key, body);
        return ResponseEntity.status(result.createdNow() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(ApiResponse.of(result.response()));
    }

    private <T> ResponseEntity<T> noStore(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(data);
    }

    @GetMapping("/outbox-health")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが源配送の有限集計を照会する管理入口")
    public ResponseEntity<ApiResponse<SourceOutboxHealthSummary>> sourceHealth(
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(sourceAdmin.health(actorId)));
    }

    @PostMapping("/outboxes/{sourceType}/{eventId}/retry")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが同じ源配送行の再予約を明示する管理入口")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = SourceOutboxAdminRetryRequest.class)))
    public ResponseEntity<ApiResponse<SourceOutboxAdminRetryAck>> retrySource(
            @PathVariable RanchRewardSourceType sourceType, @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(sourceAdmin.retry(actorId, sourceType, eventId, key, body)));
    }

    @PutMapping("/operational-controls")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが運営停止と公開設定を変更する管理入口")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = RanchOperationalControlsRequest.class)))
    public ResponseEntity<ApiResponse<RanchOperationalControlsResponse>> updateControls(
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(ApiResponse.of(facade.updateControls(actorId, key, body)));
    }

    @GetMapping("/policies")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが不変報酬政策の履歴を照会する管理入口")
    public ResponseEntity<CursorPagedResponse<RanchPolicySummary>> policies(
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        return noStore(facade.policies(actorId, cursor, limit));
    }

    @PostMapping("/policies")
    @AlwaysReachable(category = AlwaysReachableCategory.GATE_CONTROL_PLANE,
            reason = "公開停止中でもfresh SYSTEM_ADMINが将来UTC週の報酬政策を登録する管理入口")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = RanchPolicyPublicationRequest.class)))
    public ResponseEntity<ApiResponse<RanchPolicyPublicationResponse>> publishPolicy(
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        Long actorId = access.requireSelfAccess(request, response);
        var result = facade.publishPolicy(actorId, key, body);
        return ResponseEntity.status(result.createdNow() ? HttpStatus.CREATED : HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(ApiResponse.of(result.response()));
    }
}
