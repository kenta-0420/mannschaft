package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F08.7 シフト予算 閾値超過警告 サービス（API #9 / #10、Phase 9-δ 第2段）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #9-#10 / §6.2.5 / §9.1 / §9.5 に準拠。</p>
 *
 * <p>API #9 list: {@code BUDGET_VIEW} 権限で閲覧可。
 * API #10 acknowledge: {@code BUDGET_ADMIN} 権限が必要（クリーンカット）。</p>
 *
 * <p>多テナント分離: 全 API で {@code organization_id} を強制チェック。
 * 別組織の alert ID を指定したアクセスは IDOR 対策で 404 を返す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BudgetThresholdAlertService {

    /** 一覧の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final BudgetThresholdAlertRepository alertRepository;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * 組織配下の未承認警告一覧を取得する（API #9）。
     *
     * <p>並び順: {@code triggered_at DESC}（新しい警告ほど上）。</p>
     * <p>権限: {@code BUDGET_VIEW}</p>
     */
    public List<AlertResponse> list(Long organizationId, int page, int size) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        List<BudgetThresholdAlertEntity> entities =
                alertRepository.findUnacknowledgedByOrg(organizationId, pageable);

        return entities.stream().map(AlertResponse::from).toList();
    }

    /**
     * 警告を承認応答する（API #10）。
     *
     * <p>処理順:</p>
     * <ol>
     *   <li>BUDGET_ADMIN 権限チェック</li>
     *   <li>alert を取得し、所属 allocation の {@code organization_id} と一致しなければ 404
     *       (IDOR 対策、多テナント分離)</li>
     *   <li>{@code acknowledge()} で {@code acknowledged_at}/{@code acknowledged_by} をセット</li>
     *   <li>監査ログ {@code BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED}</li>
     * </ol>
     *
     * <p>多重 acknowledge は冪等扱い（最後の応答で上書き、エラーは返さない）。</p>
     *
     * <p>権限: {@code BUDGET_ADMIN}</p>
     */
    @Transactional
    public AlertResponse acknowledge(Long organizationId, Long alertId, String comment) {
        featureService.requireEnabled(organizationId);
        requireBudgetAdmin(organizationId);

        BudgetThresholdAlertEntity alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALERT_NOT_FOUND));

        // 多テナント分離: 所属 allocation を経由して organization_id 一致を検証
        ShiftBudgetAllocationEntity allocation = allocationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(alert.getAllocationId(), organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALERT_NOT_FOUND));

        Long currentUserId = SecurityUtils.getCurrentUserId();
        alert.acknowledge(currentUserId);
        BudgetThresholdAlertEntity saved = alertRepository.save(alert);

        log.info("シフト予算 警告を承認: alertId={}, allocationId={}, ackedBy={}",
                alertId, allocation.getId(), currentUserId);

        auditLogService.record(
                "BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED",
                currentUserId, null,
                allocation.getTeamId(), organizationId,
                null, null, null,
                String.format("{\"alert_id\":%d,\"allocation_id\":%d,\"threshold_percent\":%d,"
                                + "\"comment\":\"%s\"}",
                        alertId, allocation.getId(), saved.getThresholdPercent(),
                        escapeJson(comment != null ? comment : "")));

        return AlertResponse.from(saved);
    }

    private void requireBudgetView(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    private void requireBudgetAdmin(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_ADMIN")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED);
        }
    }

    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
