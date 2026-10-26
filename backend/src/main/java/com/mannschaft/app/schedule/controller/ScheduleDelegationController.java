package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CreateScheduleDelegationRequest;
import com.mannschaft.app.schedule.dto.ScheduleDelegationListResponse;
import com.mannschaft.app.schedule.dto.ScheduleDelegationMeResponse;
import com.mannschaft.app.schedule.dto.ScheduleDelegationResponse;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import com.mannschaft.app.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * スケジュール代理出席コントローラー（F03.10 §4.1）。
 *
 * <p>代理指定・取消・一覧（ADMIN）・自状況確認・承認・拒否の 6 エンドポイントを提供する。
 * ロジックは {@link ScheduleDelegationService}（第二陣）に委譲し、本クラスは認可（スコープメンバー /
 * ADMIN 判定・IDOR チェック）と DTO 組み立て（氏名解決）のみを担当する。</p>
 *
 * <p>認証は SecurityConfig の deny-by-default（{@code anyRequest().authenticated()}）により全 EP で必須。
 * エラーは {@link BusinessException} を投げ、HTTP ステータスは GlobalExceptionHandler のマッピング
 * （SCHEDULE_070-080 → 403/404/409/422）に委ねる。レートリミット（POST 10req/分）は
 * {@link com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter} が担当する。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "スケジュール代理出席", description = "F03.10 スケジュール代理出席 API")
@RequiredArgsConstructor
public class ScheduleDelegationController {

    private final ScheduleDelegationService delegationService;
    private final ScheduleService scheduleService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    /**
     * 代理を指定する（委任者が操作。MEMBER+）。
     */
    @PostMapping("/schedules/{scheduleId}/delegations")
    @Operation(summary = "代理指定", description = "F03.10 §4.1: 委任者が代理人を指定する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "指定成功")
    public ResponseEntity<ApiResponse<ScheduleDelegationResponse>> create(
            @PathVariable Long scheduleId,
            @Valid @RequestBody CreateScheduleDelegationRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 認可根治 Wave4: 代理の指定はスケジュール実体由来のスコープを閲覧できる利用者に限る。
        // 判定は副作用・応答より前の入口で行う。
        scheduleService.checkScopeViewAccess(scheduleId, currentUserId);
        ScheduleDelegationEntity delegation = delegationService.createDelegation(
                scheduleId, currentUserId, request.getDelegateId(), request.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(delegation)));
    }

    /**
     * 自分の代理を取り消す（委任者。MEMBER+）。
     */
    @DeleteMapping("/schedules/{scheduleId}/delegations/me")
    @Operation(summary = "代理取り消し", description = "F03.10 §4.1: 委任者が自分の代理を取り消す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取り消し成功")
    public ResponseEntity<Void> withdraw(@PathVariable Long scheduleId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 取り消せるのは自分が委任者である代理のみ（リポジトリクエリが delegatorId で束縛される）。
        // 加えて、スケジュール実体由来のスコープを閲覧できることを入口で確認する。
        scheduleService.checkScopeViewAccess(scheduleId, currentUserId);
        delegationService.withdraw(scheduleId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 代理一覧を取得する（ADMIN。ページネーション）。
     */
    @GetMapping("/schedules/{scheduleId}/delegations")
    @Operation(summary = "代理一覧（ADMIN）", description = "F03.10 §4.1: 管理者が代理委任の一覧を取得する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleDelegationListResponse>> list(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        requireAdmin(schedule);

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<ScheduleDelegationEntity> result = delegationService.listForAdmin(scheduleId, pageable);

        List<ScheduleDelegationResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        ScheduleDelegationListResponse response = ScheduleDelegationListResponse.builder()
                .delegations(items)
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の代理状況を確認する（MEMBER+）。
     */
    @GetMapping("/schedules/{scheduleId}/delegations/me")
    @Operation(summary = "自分の代理状況", description = "F03.10 §4.1: 委任者/代理人としての自分の代理状況を取得する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleDelegationMeResponse>> me(@PathVariable Long scheduleId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 返す委任行は delegatorId / delegateId が本人に束縛されるが、
        // スケジュールの実在有無を観測させないため、閲覧認可を入口で行う。
        scheduleService.checkScopeViewAccess(scheduleId, currentUserId);
        ScheduleDelegationResponse asDelegator = delegationService
                .findAsDelegator(scheduleId, currentUserId)
                .map(this::toResponse)
                .orElse(null);
        ScheduleDelegationResponse asDelegate = delegationService
                .findAsDelegate(scheduleId, currentUserId)
                .map(this::toResponse)
                .orElse(null);
        ScheduleDelegationMeResponse response = ScheduleDelegationMeResponse.builder()
                .asDelegator(asDelegator)
                .asDelegate(asDelegate)
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 代理を承認する（代理人本人。MEMBER+）。
     */
    @PatchMapping("/schedule-delegations/{delegationId}/accept")
    @Operation(summary = "代理承認", description = "F03.10 §4.1: 代理人が代理を承認する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<ScheduleDelegationResponse>> accept(
            @PathVariable UUID delegationId) {
        ScheduleDelegationEntity delegation = delegationService.accept(
                delegationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(delegation)));
    }

    /**
     * 代理を拒否する（代理人本人。MEMBER+）。
     */
    @PatchMapping("/schedule-delegations/{delegationId}/reject")
    @Operation(summary = "代理拒否", description = "F03.10 §4.1: 代理人が代理を拒否する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "拒否成功")
    public ResponseEntity<ApiResponse<ScheduleDelegationResponse>> reject(
            @PathVariable UUID delegationId) {
        ScheduleDelegationEntity delegation = delegationService.reject(
                delegationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(delegation)));
    }

    // ---- private ----

    /**
     * 一覧 API 用: ログインユーザーが当該スケジュールのスコープで ADMIN/DEPUTY_ADMIN かを検証する（§2）。
     * SYSTEM_ADMIN は無条件で許可する。
     */
    private void requireAdmin(ScheduleEntity schedule) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        Long scopeId = schedule.getOrganizationId() != null
                ? schedule.getOrganizationId() : schedule.getTeamId();
        String scopeType = schedule.getOrganizationId() != null ? "ORGANIZATION" : "TEAM";
        if (scopeId == null || !accessControlService.isAdminOrAbove(currentUserId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * Entity → Response DTO。委任者・代理人の氏名は user ドメインから解決する。
     */
    private ScheduleDelegationResponse toResponse(ScheduleDelegationEntity delegation) {
        return ScheduleDelegationResponse.builder()
                .id(delegation.getId().toString())
                .scheduleId(delegation.getScheduleId())
                .delegatorId(delegation.getDelegatorId())
                .delegatorName(displayName(delegation.getDelegatorId()))
                .delegateId(delegation.getDelegateId())
                .delegateName(displayName(delegation.getDelegateId()))
                .status(delegation.getStatus().name())
                .reason(delegation.getReason())
                .reviewedAt(delegation.getReviewedAt())
                .createdAt(delegation.getCreatedAt())
                .build();
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse(null);
    }
}
