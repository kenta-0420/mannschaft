package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.QueueMode;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CreateCategoryRequest;
import com.mannschaft.app.queue.dto.UpdateCategoryRequest;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.repository.QueueCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 順番待ちカテゴリサービス。カテゴリのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueCategoryService {

    private final QueueCategoryRepository categoryRepository;
    private final QueueMapper queueMapper;

    /**
     * カテゴリ一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return カテゴリ一覧
     */
    public List<CategoryResponse> listCategories(QueueScopeType scopeType, Long scopeId) {
        List<QueueCategoryEntity> categories =
                categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId);
        return queueMapper.toCategoryResponseList(categories);
    }

    /**
     * カテゴリを取得する。
     *
     * @param id        カテゴリID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return カテゴリ
     */
    public CategoryResponse getCategory(Long id, QueueScopeType scopeType, Long scopeId) {
        QueueCategoryEntity entity = findCategoryOrThrow(id, scopeType, scopeId);
        return queueMapper.toCategoryResponse(entity);
    }

    /**
     * カテゴリを作成する。
     *
     * @param request   作成リクエスト
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 作成されたカテゴリ
     */
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request,
                                           QueueScopeType scopeType, Long scopeId) {
        QueueMode queueMode = request.getQueueMode() != null
                ? QueueMode.valueOf(request.getQueueMode()) : QueueMode.INDIVIDUAL;

        QueueCategoryEntity entity = QueueCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .queueMode(queueMode)
                .prefixChar(request.getPrefixChar())
                .maxQueueSize(request.getMaxQueueSize() != null ? request.getMaxQueueSize() : (short) 50)
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : (short) 0)
                .build();

        QueueCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリ作成: id={}, name={}, scope={}:{}", saved.getId(), saved.getName(), scopeType, scopeId);
        return queueMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを更新する。
     *
     * @param id        カテゴリID
     * @param request   更新リクエスト
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 更新されたカテゴリ
     */
    @Transactional
    public CategoryResponse updateCategory(Long id, UpdateCategoryRequest request,
                                           QueueScopeType scopeType, Long scopeId) {
        QueueCategoryEntity entity = findCategoryOrThrow(id, scopeType, scopeId);

        QueueMode queueMode = request.getQueueMode() != null
                ? QueueMode.valueOf(request.getQueueMode()) : entity.getQueueMode();

        entity.update(
                request.getName(),
                queueMode,
                request.getPrefixChar(),
                request.getMaxQueueSize() != null ? request.getMaxQueueSize() : entity.getMaxQueueSize(),
                request.getDisplayOrder() != null ? request.getDisplayOrder() : entity.getDisplayOrder()
        );

        QueueCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリ更新: id={}, name={}", saved.getId(), saved.getName());
        return queueMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを削除する（論理削除）。
     *
     * @param id        カテゴリID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     */
    @Transactional
    public void deleteCategory(Long id, QueueScopeType scopeType, Long scopeId) {
        QueueCategoryEntity entity = findCategoryOrThrow(id, scopeType, scopeId);
        entity.softDelete();
        categoryRepository.save(entity);
        log.info("カテゴリ削除: id={}", id);
    }

    /**
     * カテゴリエンティティを取得する（内部用）。
     */
    public QueueCategoryEntity findEntityOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND));
    }

    private QueueCategoryEntity findCategoryOrThrow(Long id, QueueScopeType scopeType, Long scopeId) {
        return categoryRepository.findByIdAndScopeTypeAndScopeId(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND));
    }
}
