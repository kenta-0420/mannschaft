package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.BudgetConfigResponse;
import com.mannschaft.app.budget.dto.UpdateBudgetConfigRequest;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 予算設定サービス。スコープ別の予算設定を管理する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetConfigService {

    private final BudgetConfigRepository configRepository;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;

    private static final BigDecimal DEFAULT_APPROVAL_THRESHOLD = new BigDecimal("50000");
    private static final short DEFAULT_WARNING_PERCENT = 80;
    private static final short DEFAULT_CRITICAL_PERCENT = 95;

    /**
     * スコープの予算設定を取得する。存在しない場合はデフォルト値で作成する。
     */
    public BudgetConfigResponse getByScope(String scopeType, Long scopeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, scopeId, scopeType);

        BudgetConfigEntity config = configRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefault(scopeType, scopeId));

        return budgetMapper.toConfigResponse(config);
    }

    /**
     * スコープの予算設定を更新する。
     */
    @Transactional
    public BudgetConfigResponse update(String scopeType, Long scopeId, UpdateBudgetConfigRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        BudgetConfigEntity config = configRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElseGet(() -> createDefault(scopeType, scopeId));

        config.update(
                request.approvalThreshold() != null ? request.approvalThreshold() : config.getApprovalThreshold(),
                config.getWorkflowTemplateId(),
                request.autoApproveEnabled() != null ? request.autoApproveEnabled() : config.getAutoRecordPayments(),
                config.getDefaultIncomeCategoryId(),
                request.warningThresholdPercent() != null ? request.warningThresholdPercent().shortValue() : config.getBudgetWarningThreshold(),
                request.criticalThresholdPercent() != null ? request.criticalThresholdPercent().shortValue() : config.getBudgetCriticalThreshold()
        );

        BudgetConfigEntity saved = configRepository.save(config);
        log.info("予算設定を更新しました: scopeType={}, scopeId={}", scopeType, scopeId);
        return budgetMapper.toConfigResponse(saved);
    }

    // ========================================
    // ヘルパー
    // ========================================

    private BudgetConfigEntity createDefault(String scopeType, Long scopeId) {
        BudgetConfigEntity config = BudgetConfigEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .approvalThreshold(DEFAULT_APPROVAL_THRESHOLD)
                .budgetWarningThreshold(DEFAULT_WARNING_PERCENT)
                .budgetCriticalThreshold(DEFAULT_CRITICAL_PERCENT)
                .autoRecordPayments(true)
                .build();
        return configRepository.save(config);
    }
}
