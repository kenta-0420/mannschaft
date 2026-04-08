package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentSubcategoryRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentSubcategoryResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentSubcategoryEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentSubcategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.11 募集型予約: サブカテゴリサービス。
 * 設計書 §9.6 — チーム/組織が任意で追加するサブカテゴリの CRUD。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentSubcategoryService {

    private final RecruitmentSubcategoryRepository subcategoryRepository;
    private final RecruitmentCategoryRepository categoryRepository;
    private final AccessControlService accessControlService;
    private final RecruitmentMapper mapper;

    /** 指定スコープのサブカテゴリを取得する。 */
    public List<RecruitmentSubcategoryResponse> listByScope(
            RecruitmentScopeType scopeType, Long scopeId, Long categoryId) {
        List<RecruitmentSubcategoryEntity> entities;
        if (categoryId != null) {
            entities = subcategoryRepository
                    .findByScopeTypeAndScopeIdAndCategoryIdOrderByDisplayOrderAsc(scopeType, scopeId, categoryId);
        } else {
            entities = subcategoryRepository
                    .findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId);
        }
        return mapper.toSubcategoryResponseList(entities);
    }

    /** サブカテゴリを作成する (ADMIN 以上)。 */
    @Transactional
    public RecruitmentSubcategoryResponse create(
            RecruitmentScopeType scopeType, Long scopeId, Long userId,
            CreateRecruitmentSubcategoryRequest request) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        if (!categoryRepository.existsById(request.getCategoryId())) {
            throw new BusinessException(RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED);
        }

        // 同名重複チェック (論理削除済みは別レコードとして扱う)
        if (subcategoryRepository.existsByScopeTypeAndScopeIdAndCategoryIdAndName(
                scopeType, scopeId, request.getCategoryId(), request.getName())) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        RecruitmentSubcategoryEntity entity = RecruitmentSubcategoryEntity.builder()
                .categoryId(request.getCategoryId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .createdBy(userId)
                .build();

        RecruitmentSubcategoryEntity saved = subcategoryRepository.save(entity);
        log.info("F03.11 サブカテゴリ作成: scopeType={}, scopeId={}, name={}, id={}",
                scopeType, scopeId, request.getName(), saved.getId());
        return mapper.toSubcategoryResponse(saved);
    }

    /** サブカテゴリを論理削除する。 */
    @Transactional
    public void archive(Long subcategoryId, RecruitmentScopeType scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());

        RecruitmentSubcategoryEntity entity = subcategoryRepository
                .findByIdAndScopeTypeAndScopeId(subcategoryId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        entity.softDelete();
        subcategoryRepository.save(entity);
        log.info("F03.11 サブカテゴリ削除: id={}", subcategoryId);
    }
}
