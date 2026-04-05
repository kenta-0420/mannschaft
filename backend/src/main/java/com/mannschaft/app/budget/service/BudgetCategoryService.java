package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.CategoryResponse;
import com.mannschaft.app.budget.dto.CategoryTreeResponse;
import com.mannschaft.app.budget.dto.CreateCategoryRequest;
import com.mannschaft.app.budget.dto.UpdateCategoryRequest;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 予算カテゴリサービス。カテゴリのCRUD・ツリー構築・前年度コピーを担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BudgetCategoryService {

    private final BudgetCategoryRepository categoryRepository;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;

    /**
     * カテゴリを作成する。
     */
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request, Long scopeId, String scopeType) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        BudgetCategoryEntity entity = BudgetCategoryEntity.builder()
                .fiscalYearId(request.fiscalYearId())
                .name(request.name())
                .categoryType(BudgetCategoryType.valueOf(request.categoryType()))
                .parentId(request.parentId())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
                .description(request.description())
                .build();

        BudgetCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリを作成しました: id={}, name={}", saved.getId(), saved.getName());
        return budgetMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを更新する。
     */
    @Transactional
    public CategoryResponse update(Long id, UpdateCategoryRequest request, Long scopeId, String scopeType) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        BudgetCategoryEntity entity = findById(id);
        BudgetCategoryEntity updated = entity.toBuilder()
                .name(request.name())
                .sortOrder(request.sortOrder() != null ? request.sortOrder() : entity.getSortOrder())
                .description(request.description())
                .build();

        BudgetCategoryEntity saved = categoryRepository.save(updated);
        log.info("カテゴリを更新しました: id={}", saved.getId());
        return budgetMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを削除する。
     */
    @Transactional
    public void delete(Long id, Long scopeId, String scopeType) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, scopeId, scopeType);

        BudgetCategoryEntity entity = findById(id);

        // 子カテゴリが存在する場合は削除不可
        List<BudgetCategoryEntity> children = categoryRepository.findByParentId(id);
        if (!children.isEmpty()) {
            throw new BusinessException(BudgetErrorCode.BUDGET_005);
        }

        categoryRepository.delete(entity);
        log.info("カテゴリを削除しました: id={}", id);
    }

    /**
     * 会計年度のカテゴリ一覧をツリー構造で取得する。
     */
    public List<CategoryTreeResponse> listByFiscalYear(Long fiscalYearId) {
        List<BudgetCategoryEntity> allCategories = categoryRepository.findByFiscalYearId(fiscalYearId);
        return buildTree(allCategories);
    }

    /**
     * 会計年度のカテゴリをフラットリストで取得する。
     */
    public List<CategoryResponse> listFlatByFiscalYear(Long fiscalYearId) {
        return categoryRepository.findByFiscalYearId(fiscalYearId)
                .stream()
                .map(budgetMapper::toCategoryResponse)
                .toList();
    }

    /**
     * 前年度からカテゴリをコピーする。
     */
    @Transactional
    public void copyFromPreviousYear(Long sourceFiscalYearId, Long targetFiscalYearId) {
        List<BudgetCategoryEntity> sourceCategories = categoryRepository.findByFiscalYearId(sourceFiscalYearId);

        // 親ID→新IDのマッピング（ツリー構造を保持するため）
        Map<Long, Long> oldToNewIdMap = new HashMap<>();

        // まずルートカテゴリ（parentId=null）をコピー
        List<BudgetCategoryEntity> roots = sourceCategories.stream()
                .filter(c -> c.getParentId() == null)
                .toList();
        for (BudgetCategoryEntity root : roots) {
            BudgetCategoryEntity newEntity = copyCategory(root, targetFiscalYearId, null);
            BudgetCategoryEntity saved = categoryRepository.save(newEntity);
            oldToNewIdMap.put(root.getId(), saved.getId());
        }

        // 子カテゴリをコピー（再帰的に）
        copyChildren(sourceCategories, targetFiscalYearId, oldToNewIdMap);

        log.info("前年度カテゴリをコピーしました: source={}, target={}, count={}",
                sourceFiscalYearId, targetFiscalYearId, sourceCategories.size());
    }

    // ========================================
    // ヘルパー
    // ========================================

    BudgetCategoryEntity findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_006));
    }

    private List<CategoryTreeResponse> buildTree(List<BudgetCategoryEntity> categories) {
        Map<Long, List<BudgetCategoryEntity>> childrenMap = new HashMap<>();
        List<BudgetCategoryEntity> roots = new ArrayList<>();

        for (BudgetCategoryEntity cat : categories) {
            if (cat.getParentId() == null) {
                roots.add(cat);
            } else {
                childrenMap.computeIfAbsent(cat.getParentId(), k -> new ArrayList<>()).add(cat);
            }
        }

        return roots.stream()
                .map(root -> buildTreeNode(root, childrenMap))
                .toList();
    }

    private CategoryTreeResponse buildTreeNode(BudgetCategoryEntity entity,
                                                Map<Long, List<BudgetCategoryEntity>> childrenMap) {
        List<CategoryTreeResponse> children = childrenMap.getOrDefault(entity.getId(), List.of())
                .stream()
                .map(child -> buildTreeNode(child, childrenMap))
                .toList();

        return new CategoryTreeResponse(
                entity.getId(),
                entity.getFiscalYearId(),
                entity.getName(),
                entity.getCategoryType().name(),
                entity.getParentId(),
                entity.getSortOrder(),
                entity.getDescription(),
                BigDecimal.ZERO,
                children
        );
    }

    private void copyChildren(List<BudgetCategoryEntity> sourceCategories, Long targetFiscalYearId,
                               Map<Long, Long> oldToNewIdMap) {
        boolean copied = true;
        while (copied) {
            copied = false;
            for (BudgetCategoryEntity source : sourceCategories) {
                if (source.getParentId() != null
                        && oldToNewIdMap.containsKey(source.getParentId())
                        && !oldToNewIdMap.containsKey(source.getId())) {
                    Long newParentId = oldToNewIdMap.get(source.getParentId());
                    BudgetCategoryEntity newEntity = copyCategory(source, targetFiscalYearId, newParentId);
                    BudgetCategoryEntity saved = categoryRepository.save(newEntity);
                    oldToNewIdMap.put(source.getId(), saved.getId());
                    copied = true;
                }
            }
        }
    }

    private BudgetCategoryEntity copyCategory(BudgetCategoryEntity source, Long targetFiscalYearId, Long newParentId) {
        return BudgetCategoryEntity.builder()
                .fiscalYearId(targetFiscalYearId)
                .name(source.getName())
                .categoryType(source.getCategoryType())
                .parentId(newParentId)
                .sortOrder(source.getSortOrder())
                .description(source.getDescription())
                .build();
    }
}
