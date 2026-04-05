package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.skill.SkillErrorCode;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * スキルカテゴリ管理サービス。
 * カテゴリのCRUD・スコープ別取得を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SkillCategoryService {

    private final SkillCategoryRepository categoryRepository;

    /**
     * スコープに紐づくカテゴリ一覧を取得する。
     *
     * @param scopeType       スコープ種別
     * @param scopeId         スコープID
     * @param includeInactive trueの場合は非アクティブも含める
     * @return カテゴリエンティティ一覧
     */
    public ApiResponse<List<SkillCategoryEntity>> getCategories(
            String scopeType, Long scopeId, boolean includeInactive) {
        List<SkillCategoryEntity> categories;
        if (includeInactive) {
            categories = categoryRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        } else {
            categories = categoryRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId);
        }
        return ApiResponse.of(categories);
    }

    /**
     * カテゴリを作成する。同一スコープ内での name 重複はエラー。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param createdBy 作成者ユーザーID
     * @param name      カテゴリ名
     * @param description 説明（任意）
     * @param icon      アイコン（任意）
     * @param sortOrder 並び順
     * @return 作成したカテゴリエンティティ
     */
    @Transactional
    public ApiResponse<SkillCategoryEntity> createCategory(
            String scopeType, Long scopeId, Long createdBy,
            String name, String description, String icon, Integer sortOrder) {

        // name 重複チェック（同一スコープ・deleted_at IS NULL）
        List<SkillCategoryEntity> existing =
                categoryRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
        boolean duplicated = existing.stream()
                .anyMatch(c -> c.getName().equals(name));
        if (duplicated) {
            throw new BusinessException(SkillErrorCode.SKILL_001);
        }

        SkillCategoryEntity entity = SkillCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .createdBy(createdBy)
                .name(name)
                .description(description)
                .icon(icon)
                .sortOrder(sortOrder != null ? sortOrder : 0)
                .build();

        SkillCategoryEntity saved = categoryRepository.save(entity);
        log.info("スキルカテゴリ作成: id={}, scope={}/{}, name={}", saved.getId(), scopeType, scopeId, name);
        return ApiResponse.of(saved);
    }

    /**
     * カテゴリを更新する。所有スコープ確認・name 重複チェック（自身を除く）を行う。
     *
     * @param id        カテゴリID
     * @param scopeType スコープ種別（所有確認用）
     * @param scopeId   スコープID（所有確認用）
     * @param name      新カテゴリ名（nullの場合は変更なし）
     * @param description 新説明（nullの場合は変更なし）
     * @param icon      新アイコン（nullの場合は変更なし）
     * @param sortOrder 新並び順（nullの場合は変更なし）
     * @param isActive  有効フラグ（nullの場合は変更なし）
     * @return 更新後カテゴリエンティティ
     */
    @Transactional
    public ApiResponse<SkillCategoryEntity> updateCategory(
            Long id, String scopeType, Long scopeId,
            String name, String description, String icon, Integer sortOrder, Boolean isActive) {

        SkillCategoryEntity category = findCategoryOrThrow(id);

        // 所有スコープ確認
        if (!category.getScopeType().equals(scopeType) || !category.getScopeId().equals(scopeId)) {
            throw new BusinessException(SkillErrorCode.SKILL_003);
        }

        // name 重複チェック（自身を除く）
        if (name != null && !name.equals(category.getName())) {
            List<SkillCategoryEntity> existing =
                    categoryRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId);
            boolean duplicated = existing.stream()
                    .filter(c -> !c.getId().equals(id))
                    .anyMatch(c -> c.getName().equals(name));
            if (duplicated) {
                throw new BusinessException(SkillErrorCode.SKILL_001);
            }
        }

        // toBuilder でフィールドを更新
        SkillCategoryEntity updated = category.toBuilder()
                .name(name != null ? name : category.getName())
                .description(description != null ? description : category.getDescription())
                .icon(icon != null ? icon : category.getIcon())
                .sortOrder(sortOrder != null ? sortOrder : category.getSortOrder())
                .isActive(isActive != null ? isActive : category.getIsActive())
                .build();

        SkillCategoryEntity saved = categoryRepository.save(updated);
        log.info("スキルカテゴリ更新: id={}", id);
        return ApiResponse.of(saved);
    }

    /**
     * カテゴリを論理削除する。member_skills は保持（category_id のNULL化はしない）。
     *
     * @param id        カテゴリID
     * @param scopeType スコープ種別（所有確認用）
     * @param scopeId   スコープID（所有確認用）
     */
    @Transactional
    public void deleteCategory(Long id, String scopeType, Long scopeId) {
        SkillCategoryEntity category = findCategoryOrThrow(id);

        // 所有スコープ確認
        if (!category.getScopeType().equals(scopeType) || !category.getScopeId().equals(scopeId)) {
            throw new BusinessException(SkillErrorCode.SKILL_003);
        }

        category.softDelete();
        categoryRepository.save(category);
        log.info("スキルカテゴリ論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでカテゴリを取得する。見つからない場合は SKILL_001 例外。
     */
    public SkillCategoryEntity findCategoryOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SkillErrorCode.SKILL_001));
    }
}
