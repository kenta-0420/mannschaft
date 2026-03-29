package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetFiscalYearStatus;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.CreateFiscalYearRequest;
import com.mannschaft.app.budget.dto.FiscalYearResponse;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会計年度サービス。会計年度のCRUD・クローズ・再開を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetFiscalYearService {

    private final BudgetFiscalYearRepository fiscalYearRepository;
    private final BudgetCategoryService categoryService;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;

    /**
     * 会計年度を作成する。
     */
    @Transactional
    public FiscalYearResponse create(CreateFiscalYearRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, request.scopeId(), request.scopeType());

        if (!request.startDate().isBefore(request.endDate())) {
            throw new BusinessException(BudgetErrorCode.BUDGET_001);
        }

        // 同一スコープ・期間の重複チェック
        List<BudgetFiscalYearEntity> overlapping = fiscalYearRepository
                .findByScopeTypeAndScopeId(request.scopeType(), request.scopeId())
                .stream()
                .filter(fy -> !fy.getEndDate().isBefore(request.startDate())
                        && !fy.getStartDate().isAfter(request.endDate()))
                .toList();
        if (!overlapping.isEmpty()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_002);
        }

        BudgetFiscalYearEntity entity = BudgetFiscalYearEntity.builder()
                .name(request.name())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .scopeId(request.scopeId())
                .scopeType(request.scopeType())
                .status(BudgetFiscalYearStatus.OPEN)
                .createdBy(currentUserId)
                .build();

        BudgetFiscalYearEntity saved = fiscalYearRepository.save(entity);
        log.info("会計年度を作成しました: id={}, name={}", saved.getId(), saved.getName());
        return budgetMapper.toFiscalYearResponse(saved);
    }

    /**
     * 会計年度をIDで取得する。
     */
    public FiscalYearResponse getById(Long id) {
        BudgetFiscalYearEntity entity = findById(id);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, entity.getScopeId(), entity.getScopeType());
        return budgetMapper.toFiscalYearResponse(entity);
    }

    /**
     * スコープ内の会計年度一覧を取得する。
     */
    public List<FiscalYearResponse> listByScope(String scopeType, Long scopeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, scopeId, scopeType);

        return fiscalYearRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .stream()
                .map(budgetMapper::toFiscalYearResponse)
                .toList();
    }

    /**
     * 会計年度を更新する。
     */
    @Transactional
    public FiscalYearResponse update(Long id, CreateFiscalYearRequest request) {
        BudgetFiscalYearEntity entity = findById(id);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, entity.getScopeId(), entity.getScopeType());

        checkOpen(entity);

        BudgetFiscalYearEntity updated = entity.toBuilder()
                .name(request.name())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();

        BudgetFiscalYearEntity saved = fiscalYearRepository.save(updated);
        log.info("会計年度を更新しました: id={}", saved.getId());
        return budgetMapper.toFiscalYearResponse(saved);
    }

    /**
     * 会計年度をクローズする。
     */
    @Transactional
    public FiscalYearResponse close(Long id) {
        BudgetFiscalYearEntity entity = findById(id);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, entity.getScopeId(), entity.getScopeType());

        checkOpen(entity);

        entity.close();
        BudgetFiscalYearEntity saved = fiscalYearRepository.save(entity);
        log.info("会計年度をクローズしました: id={}", saved.getId());
        return budgetMapper.toFiscalYearResponse(saved);
    }

    /**
     * 会計年度を再開する。
     */
    @Transactional
    public FiscalYearResponse reopen(Long id) {
        BudgetFiscalYearEntity entity = findById(id);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, entity.getScopeId(), entity.getScopeType());

        if (entity.getStatus() != BudgetFiscalYearStatus.CLOSED) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }

        entity.reopen();
        BudgetFiscalYearEntity saved = fiscalYearRepository.save(entity);
        log.info("会計年度を再開しました: id={}", saved.getId());
        return budgetMapper.toFiscalYearResponse(saved);
    }

    /**
     * 会計年度を削除する（論理削除）。
     */
    @Transactional
    public void delete(Long id) {
        BudgetFiscalYearEntity entity = findById(id);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, entity.getScopeId(), entity.getScopeType());

        fiscalYearRepository.delete(entity);
        log.info("会計年度を削除しました: id={}", id);
    }

    /**
     * 前年度からカテゴリをコピーする。
     */
    @Transactional
    public void copyCategories(Long sourceFiscalYearId, Long targetFiscalYearId) {
        BudgetFiscalYearEntity source = findById(sourceFiscalYearId);
        BudgetFiscalYearEntity target = findById(targetFiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, target.getScopeId(), target.getScopeType());

        checkOpen(target);

        categoryService.copyFromPreviousYear(source.getId(), target.getId());
        log.info("カテゴリをコピーしました: source={}, target={}", sourceFiscalYearId, targetFiscalYearId);
    }

    // ========================================
    // ヘルパー（package-private）
    // ========================================

    BudgetFiscalYearEntity findById(Long id) {
        return fiscalYearRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_003));
    }

    private void checkOpen(BudgetFiscalYearEntity entity) {
        if (entity.getStatus() != BudgetFiscalYearStatus.OPEN) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }
    }
}
