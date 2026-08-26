package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
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

    /** 認可根治戦役 Wave3-B3: incident カテゴリ管理への認可敷設で使用する。 */
    private final AccessControlService accessControlService;

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
        // 認可: ADMIN相当（scopeId/scopeTypeはリクエスト由来）
        accessControlService.checkAdminOrAbove(userId, req.scopeId(), req.scopeType());

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
     * <p>認可: MEMBER以上（scopeId/scopeTypeはクエリパラメータ由来）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    呼び出しユーザーID
     * @return カテゴリレスポンス一覧
     */
    public List<IncidentCategoryResponse> listCategories(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType);

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
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     カテゴリID
     * @param req    更新リクエスト
     * @param userId 呼び出しユーザーID
     * @return 更新後カテゴリレスポンス
     */
    @Transactional
    public IncidentCategoryResponse updateCategory(Long id, UpdateIncidentCategoryRequest req, Long userId) {
        IncidentCategoryEntity category = findCategoryOrThrow(id);
        requireMemberOrConceal(category, userId);
        accessControlService.checkAdminOrAbove(userId, category.getScopeId(), category.getScopeType());

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
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     カテゴリID
     * @param userId 呼び出しユーザーID
     */
    @Transactional
    public void deleteCategory(Long id, Long userId) {
        IncidentCategoryEntity category = findCategoryOrThrow(id);
        requireMemberOrConceal(category, userId);
        accessControlService.checkAdminOrAbove(userId, category.getScopeId(), category.getScopeType());

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

    /**
     * 認可根治戦役 Wave3-B3 BOLA是正: ID 直指定 EP で使う共通ガード。
     * URL に scope が現れないため、entity を先に fetch した上で呼び出しユーザーが
     * entity 由来 scope のメンバーであることを検証する。非メンバーは越境 ID の存在を秘匿するため、
     * 通常の {@code checkMembership}（403）ではなく entity の NOT_FOUND コード（404）を投げる。
     */
    private void requireMemberOrConceal(IncidentCategoryEntity category, Long userId) {
        if (!accessControlService.isMember(userId, category.getScopeId(), category.getScopeType())) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_001);
        }
    }
}
