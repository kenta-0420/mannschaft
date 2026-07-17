package com.mannschaft.app.budget.service;

import com.mannschaft.app.budget.BudgetCategoryType;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.budget.BudgetMapper;
import com.mannschaft.app.budget.dto.CategoryResponse;
import com.mannschaft.app.budget.dto.CategoryTreeResponse;
import com.mannschaft.app.budget.dto.CreateCategoryRequest;
import com.mannschaft.app.budget.dto.UpdateCategoryRequest;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
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
    /**
     * 認可根治戦役 Wave3-B9: カテゴリは scopeId/scopeType を持たず fiscalYearId のみを保持するため、
     * 認可判定は親の会計年度を経由して行う必要がある（親子鎖）。
     * {@link com.mannschaft.app.budget.service.BudgetFiscalYearService} を直接注入すると
     * BudgetFiscalYearService → BudgetCategoryService → BudgetFiscalYearService の循環依存になるため
     * Repository を直接使う。
     */
    private final BudgetFiscalYearRepository fiscalYearRepository;
    private final BudgetMapper budgetMapper;
    private final AccessControlService accessControlService;

    /**
     * カテゴリを作成する。
     *
     * <p>認可根治戦役 Wave3-B9: 旧実装は path の scopeId/scopeType（クライアント指定値）で
     * checkAdminOrAbove していたが、request.fiscalYearId() が実際にそのスコープに属するかは
     * 未検証だった（BOLA: 自スコープの ADMIN 権限のまま他スコープの fiscalYearId を指定すれば
     * 越境してカテゴリを作成できた）。fiscalYearId から会計年度を fetch し、
     * クライアント指定の scopeId/scopeType が真の scope と一致することを確認してから
     * （不一致は存在秘匿のため 404）、その真の scope で checkAdminOrAbove する。</p>
     */
    @Transactional
    public CategoryResponse create(CreateCategoryRequest request, Long scopeId, String scopeType) {
        BudgetFiscalYearEntity fiscalYear = findFiscalYearOrThrow(request.fiscalYearId());
        requireFiscalYearScopeMatchOrConceal(fiscalYear, scopeId, scopeType);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fiscalYear.getScopeId(), fiscalYear.getScopeType());

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
     *
     * <p>認可根治戦役 Wave3-B9: 旧実装は path の scopeId/scopeType で checkAdminOrAbove した
     * 「後」に id 直指定で更新していたため、任意スコープの ADMIN が別スコープの categoryId を
     * 渡すだけで更新できる重大 BOLA だった。entity を先に fetch し、その親（fiscalYearId）
     * 由来の真の scope とクライアント指定値が一致することを確認してから（不一致は
     * カテゴリの存在秘匿のため 404）、その真の scope で checkAdminOrAbove する。</p>
     */
    @Transactional
    public CategoryResponse update(Long id, UpdateCategoryRequest request, Long scopeId, String scopeType) {
        BudgetCategoryEntity entity = findById(id);
        BudgetFiscalYearEntity fiscalYear = findFiscalYearOrThrow(entity.getFiscalYearId());
        requireCategoryScopeMatchOrConceal(fiscalYear, scopeId, scopeType);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fiscalYear.getScopeId(), fiscalYear.getScopeType());

        // 管理対象エンティティを直接ミューテートして id 保持＝UPDATE を保証する
        // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
        entity.applyUpdate(
                request.name(),
                request.sortOrder() != null ? request.sortOrder() : entity.getSortOrder(),
                request.description()
        );

        BudgetCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリを更新しました: id={}", saved.getId());
        return budgetMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを削除する。
     *
     * <p>認可根治戦役 Wave3-B9: 旧実装は path の scopeId/scopeType で checkAdminOrAbove した
     * 「後」に id 直指定で削除していたため、任意スコープの ADMIN が別スコープの categoryId を
     * 渡すだけで削除できる重大 BOLA だった。entity を先に fetch し、その親（fiscalYearId）
     * 由来の真の scope とクライアント指定値が一致することを確認してから（不一致は
     * カテゴリの存在秘匿のため 404）、その真の scope で checkAdminOrAbove する。</p>
     */
    @Transactional
    public void delete(Long id, Long scopeId, String scopeType) {
        BudgetCategoryEntity entity = findById(id);
        BudgetFiscalYearEntity fiscalYear = findFiscalYearOrThrow(entity.getFiscalYearId());
        requireCategoryScopeMatchOrConceal(fiscalYear, scopeId, scopeType);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(currentUserId, fiscalYear.getScopeId(), fiscalYear.getScopeType());

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
     *
     * <p>認可根治戦役 Wave3-B9: {@code BudgetCategoryController#copyFromPreviousYear} は本メソッドを
     * 直接呼び出しており、認可済みの {@link com.mannschaft.app.budget.service.BudgetFiscalYearService
     * #copyCategories}（手本・target scope で checkAdminOrAbove 済）を経由していなかった
     * ため認可ゼロだった（実質到達不能な死んだ auth ガードが別に存在していた）。
     * 本 EP は scope を宣言する query パラメータを持たない「ID 直指定」EP のため、
     * target 会計年度の scope に非所属なら存在秘匿のため 404、所属しているが ADMIN でない
     * 場合は 403。source と target が同一 scope であることも確認する
     * （他 scope の年度構成を盗用させない。不一致も存在秘匿のため 404）。</p>
     */
    @Transactional
    public void copyFromPreviousYear(Long sourceFiscalYearId, Long targetFiscalYearId) {
        BudgetFiscalYearEntity source = findFiscalYearOrThrow(sourceFiscalYearId);
        BudgetFiscalYearEntity target = findFiscalYearOrThrow(targetFiscalYearId);
        Long currentUserId = SecurityUtils.getCurrentUserId();

        if (!accessControlService.isMember(currentUserId, target.getScopeId(), target.getScopeType())) {
            throw new BusinessException(BudgetErrorCode.BUDGET_003);
        }
        accessControlService.checkAdminOrAbove(currentUserId, target.getScopeId(), target.getScopeType());

        if (!source.getScopeType().equals(target.getScopeType()) || !source.getScopeId().equals(target.getScopeId())) {
            // 他 scope の年度構成コピーを禁止する（存在秘匿のため通常の年度不在と同一コード）
            throw new BusinessException(BudgetErrorCode.BUDGET_003);
        }

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

    /**
     * 会計年度をIDで取得する（認可判定用の親子鎖解決）。
     * {@link com.mannschaft.app.budget.service.BudgetFiscalYearService#findById} と同じエラーコードを使う。
     */
    private BudgetFiscalYearEntity findFiscalYearOrThrow(Long fiscalYearId) {
        return fiscalYearRepository.findById(fiscalYearId)
                .orElseThrow(() -> new BusinessException(BudgetErrorCode.BUDGET_003));
    }

    /**
     * 認可根治戦役 Wave3-B9: create のようにクライアントが scopeId/scopeType を明示的に
     * 宣言する EP で使う BOLA ガード。fiscalYear 由来の真の scope と一致しない場合は
     * 越境 fiscalYearId の存在を秘匿するため、通常の 403 ではなく年度の NOT_FOUND コード（404）を投げる。
     */
    private void requireFiscalYearScopeMatchOrConceal(BudgetFiscalYearEntity fiscalYear, Long scopeId, String scopeType) {
        if (!fiscalYear.getScopeId().equals(scopeId) || !fiscalYear.getScopeType().equals(scopeType)) {
            throw new BusinessException(BudgetErrorCode.BUDGET_003);
        }
    }

    /**
     * 認可根治戦役 Wave3-B9: update/delete のようにクライアントが scopeId/scopeType を明示的に
     * 宣言する EP で使う BOLA ガード。カテゴリの親（fiscalYear）由来の真の scope と一致しない
     * 場合は越境 categoryId の存在を秘匿するため、カテゴリの NOT_FOUND コード（404）を投げる。
     */
    private void requireCategoryScopeMatchOrConceal(BudgetFiscalYearEntity fiscalYear, Long scopeId, String scopeType) {
        if (!fiscalYear.getScopeId().equals(scopeId) || !fiscalYear.getScopeType().equals(scopeType)) {
            throw new BusinessException(BudgetErrorCode.BUDGET_006);
        }
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
