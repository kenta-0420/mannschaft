package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.entity.IncidentCategoryEntity;
import com.mannschaft.app.incident.repository.IncidentCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * インシデントカテゴリ管理サービス。
 * カテゴリのCRUD・スコープ別取得を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class IncidentCategoryService {

    /** スコープあたりの最大カテゴリ数 */
    private static final int MAX_CATEGORIES_PER_SCOPE = 20;

    private final IncidentCategoryRepository categoryRepository;

    // ========================================
    // DTOクラス定義
    // ========================================

    /** カテゴリ作成リクエスト */
    public record CreateIncidentCategoryRequest(
            String scopeType,
            Long scopeId,
            String name,
            String description,
            String icon,
            String color,
            Integer slaHours,
            Integer sortOrder
    ) {}

    /** カテゴリ更新リクエスト */
    public record UpdateIncidentCategoryRequest(
            String name,
            String description,
            String icon,
            String color,
            Integer slaHours,
            Boolean isActive,
            Integer sortOrder
    ) {}

    /** カテゴリレスポンス */
    public record IncidentCategoryResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String description,
            String icon,
            String color,
            Integer slaHours,
            Boolean isActive,
            Integer sortOrder,
            LocalDateTime createdAt
    ) {
        public static IncidentCategoryResponse from(IncidentCategoryEntity entity) {
            return new IncidentCategoryResponse(
                    entity.getId(),
                    entity.getScopeType(),
                    entity.getScopeId(),
                    entity.getName(),
                    null,   // descriptionはエンティティに未定義のため暫定null
                    null,   // iconはエンティティに未定義のため暫定null
                    null,   // colorはエンティティに未定義のため暫定null
                    entity.getSlaHours(),
                    entity.getIsActive(),
                    null,   // sortOrderはエンティティに未定義のため暫定null
                    entity.getCreatedAt()
            );
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * カテゴリを作成する。
     * スコープ内最大20カテゴリ制限（論理削除済み除く）を検証する。
     *
     * @param userId 作成者ユーザーID
     * @param req    作成リクエスト
     * @return 作成したカテゴリレスポンス
     */
    @Transactional
    public IncidentCategoryResponse createCategory(Long userId, CreateIncidentCategoryRequest req) {
        // スコープ内のカテゴリ数チェック（論理削除済み除く）
        List<IncidentCategoryEntity> existing =
                categoryRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                        req.scopeType(), req.scopeId());
        if (existing.size() >= MAX_CATEGORIES_PER_SCOPE) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_001);
        }

        IncidentCategoryEntity entity = IncidentCategoryEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .slaHours(req.slaHours() != null ? req.slaHours() : 72)
                .isActive(true)
                .createdBy(userId)
                .build();

        IncidentCategoryEntity saved = categoryRepository.save(entity);
        log.info("インシデントカテゴリ作成: id={}, scope={}/{}, name={}",
                saved.getId(), req.scopeType(), req.scopeId(), req.name());
        return IncidentCategoryResponse.from(saved);
    }

    /**
     * スコープに紐づくアクティブカテゴリ一覧を取得する（sort_order昇順）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return カテゴリレスポンス一覧
     */
    public List<IncidentCategoryResponse> listCategories(String scopeType, Long scopeId) {
        return categoryRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId)
                .stream()
                // sortOrderがエンティティに未定義の場合はIDで代用
                .sorted(Comparator.comparing(IncidentCategoryEntity::getId))
                .map(IncidentCategoryResponse::from)
                .toList();
    }

    /**
     * カテゴリを更新する。
     *
     * @param id  カテゴリID
     * @param req 更新リクエスト
     * @return 更新後カテゴリレスポンス
     */
    @Transactional
    public IncidentCategoryResponse updateCategory(Long id, UpdateIncidentCategoryRequest req) {
        IncidentCategoryEntity category = findCategoryOrThrow(id);

        // slaHoursを更新（指定がある場合）
        if (req.slaHours() != null) {
            category.updateSlaHours(req.slaHours());
        }

        // isActiveを更新（指定がある場合）
        if (req.isActive() != null) {
            if (req.isActive()) {
                category.activate();
            } else {
                category.deactivate();
            }
        }

        IncidentCategoryEntity saved = categoryRepository.save(category);
        log.info("インシデントカテゴリ更新: id={}", id);
        return IncidentCategoryResponse.from(saved);
    }

    /**
     * カテゴリを論理削除する。
     *
     * @param id カテゴリID
     */
    @Transactional
    public void deleteCategory(Long id) {
        IncidentCategoryEntity category = findCategoryOrThrow(id);
        category.softDelete();
        categoryRepository.save(category);
        log.info("インシデントカテゴリ論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでカテゴリを取得する。見つからない場合は INCIDENT_001 例外をスロー。
     */
    public IncidentCategoryEntity findCategoryOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(IncidentErrorCode.INCIDENT_001));
    }
}
