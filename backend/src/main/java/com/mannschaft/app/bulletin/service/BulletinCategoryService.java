package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 掲示板カテゴリサービス。カテゴリのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinCategoryService {

    private final BulletinCategoryRepository categoryRepository;
    private final BulletinMapper bulletinMapper;

    /**
     * スコープのカテゴリ一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return カテゴリレスポンスリスト
     */
    public List<CategoryResponse> listCategories(ScopeType scopeType, Long scopeId) {
        List<BulletinCategoryEntity> categories =
                categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId);
        return bulletinMapper.toCategoryResponseList(categories);
    }

    /**
     * カテゴリ詳細を取得する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @return カテゴリレスポンス
     */
    public CategoryResponse getCategory(ScopeType scopeType, Long scopeId, Long categoryId) {
        BulletinCategoryEntity entity = findCategoryOrThrow(scopeType, scopeId, categoryId);
        return bulletinMapper.toCategoryResponse(entity);
    }

    /**
     * カテゴリを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse createCategory(ScopeType scopeType, Long scopeId, Long userId, CreateCategoryRequest request) {
        if (categoryRepository.existsByScopeTypeAndScopeIdAndName(scopeType, scopeId, request.getName())) {
            throw new BusinessException(BulletinErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        BulletinCategoryEntity entity = BulletinCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .color(request.getColor())
                .postMinRole(request.getPostMinRole() != null ? request.getPostMinRole() : "MEMBER_PLUS")
                .createdBy(userId)
                .build();

        BulletinCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリ作成: scopeType={}, scopeId={}, categoryId={}", scopeType, scopeId, saved.getId());
        return bulletinMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを更新する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @param request    更新リクエスト
     * @return 更新されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse updateCategory(ScopeType scopeType, Long scopeId, Long categoryId, UpdateCategoryRequest request) {
        BulletinCategoryEntity entity = findCategoryOrThrow(scopeType, scopeId, categoryId);

        if (categoryRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(scopeType, scopeId, request.getName(), categoryId)) {
            throw new BusinessException(BulletinErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        entity.update(
                request.getName(),
                request.getDescription(),
                request.getDisplayOrder() != null ? request.getDisplayOrder() : entity.getDisplayOrder(),
                request.getColor(),
                request.getPostMinRole() != null ? request.getPostMinRole() : entity.getPostMinRole()
        );

        BulletinCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリ更新: categoryId={}", categoryId);
        return bulletinMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを論理削除する。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     */
    @Transactional
    public void deleteCategory(ScopeType scopeType, Long scopeId, Long categoryId) {
        BulletinCategoryEntity entity = findCategoryOrThrow(scopeType, scopeId, categoryId);
        entity.softDelete();
        categoryRepository.save(entity);
        log.info("カテゴリ削除: categoryId={}", categoryId);
    }

    /**
     * カテゴリエンティティを取得する。存在しない場合は例外をスローする。
     */
    BulletinCategoryEntity findCategoryOrThrow(ScopeType scopeType, Long scopeId, Long categoryId) {
        return categoryRepository.findByIdAndScopeTypeAndScopeId(categoryId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.CATEGORY_NOT_FOUND));
    }
}
