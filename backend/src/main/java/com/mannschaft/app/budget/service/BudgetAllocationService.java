package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.AllocationRequest;
import com.mannschaft.app.budget.dto.AllocationResponse;
import com.mannschaft.app.budget.dto.BulkAllocationRequest;
import com.mannschaft.app.budget.dto.BulkAllocationResponse;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 予算配分サービス。月別配分のCRUD・一括更新を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetAllocationService {

    private final BudgetAllocationRepository allocationRepository;
    private final BudgetFiscalYearService fiscalYearService;
    private final BudgetCategoryService categoryService;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;

    /**
     * 会計年度の配分一覧を取得する。
     */
    public List<AllocationResponse> listByFiscalYear(Long fiscalYearId) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(fiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, fy.getScopeId(), fy.getScopeType());

        return allocationRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .map(entity -> {
                    AllocationResponse response = budgetMapper.toAllocationResponse(entity);
                    BudgetCategoryEntity category = categoryService.findById(entity.getCategoryId());
                    return new AllocationResponse(
                            response.id(),
                            response.categoryId(),
                            category.getName(),
                            response.month(),
                            response.amount(),
                            response.createdAt(),
                            response.updatedAt()
                    );
                })
                .toList();
    }

    /**
     * 配分を一括UPSERT（作成 or 更新）する。
     */
    @Transactional
    public BulkAllocationResponse bulkUpsert(BulkAllocationRequest request) {
        BudgetFiscalYearEntity fy = fiscalYearService.findById(request.fiscalYearId());
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fy.getScopeId(), fy.getScopeType());

        if (fy.getStatus() != com.mannschaft.app.budget.BudgetFiscalYearStatus.OPEN) {
            throw new BusinessException(BudgetErrorCode.BUDGET_004);
        }

        int createdCount = 0;
        int updatedCount = 0;
        List<AllocationResponse> results = new ArrayList<>();

        for (AllocationRequest alloc : request.allocations()) {
            BudgetCategoryEntity category = categoryService.findById(alloc.categoryId());

            Optional<BudgetAllocationEntity> existing = allocationRepository
                    .findByFiscalYearIdAndCategoryId(request.fiscalYearId(), alloc.categoryId());

            BudgetAllocationEntity entity;
            if (existing.isPresent()) {
                entity = existing.get();
                entity.updateAmount(alloc.amount(), null);
                updatedCount++;
            } else {
                entity = BudgetAllocationEntity.builder()
                        .fiscalYearId(request.fiscalYearId())
                        .categoryId(alloc.categoryId())
                        .amount(alloc.amount())
                        .build();
                createdCount++;
            }

            BudgetAllocationEntity saved = allocationRepository.save(entity);
            AllocationResponse response = budgetMapper.toAllocationResponse(saved);
            results.add(new AllocationResponse(
                    response.id(),
                    response.categoryId(),
                    category.getName(),
                    response.month(),
                    response.amount(),
                    response.createdAt(),
                    response.updatedAt()
            ));
        }

        log.info("配分を一括更新しました: fiscalYearId={}, created={}, updated={}",
                request.fiscalYearId(), createdCount, updatedCount);

        return new BulkAllocationResponse(
                request.allocations().size(),
                createdCount,
                updatedCount,
                results
        );
    }
}
