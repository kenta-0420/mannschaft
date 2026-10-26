package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.DeleteCategoryResponse;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 掲示板カテゴリサービス。カテゴリのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinCategoryService {

    private final BulletinCategoryRepository categoryRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinMapper bulletinMapper;
    private final BulletinAccessGuard accessGuard;
    /** F17.1 村掲示板グローバル方式: 村スコープの閲覧認可（可視性ゲート）を委譲する。 */
    private final VillageBulletinAccessService villageBulletinAccessService;

    /** post_min_role のデフォルト（設計書 F05.1 §3）。権限ロール体系に実在する値のみ使用する。 */
    private static final String DEFAULT_POST_MIN_ROLE = "MEMBER";

    /**
     * スコープのカテゴリ一覧を取得する。所属メンバーのみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    操作ユーザーID
     * @return カテゴリレスポンスリスト
     */
    public List<CategoryResponse> listCategories(ScopeType scopeType, Long scopeId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        List<BulletinCategoryEntity> categories =
                categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId);
        return bulletinMapper.toCategoryResponseList(categories);
    }

    /**
     * 村スコープのカテゴリ一覧を取得する（F17.1 村掲示板グローバル方式）。
     *
     * <p>村の {@code bulletin_visibility} に基づく閲覧認可を {@link VillageBulletinAccessService}
     * に委譲し、許可された場合のみ {@code scope_village_id} 一致のカテゴリを表示順で返す。</p>
     *
     * @param villageId 村 ID（必須）
     * @param userId    操作ユーザーID
     * @return カテゴリレスポンスリスト
     */
    public List<CategoryResponse> listVillageCategories(UUID villageId, Long userId) {
        villageBulletinAccessService.checkVillageBulletinViewAccess(villageId, userId);
        List<BulletinCategoryEntity> categories =
                categoryRepository.findByScopeVillageIdOrderByDisplayOrderAsc(villageId);
        return bulletinMapper.toCategoryResponseList(categories);
    }

    /**
     * カテゴリ詳細を取得する。所属メンバーのみ。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @param userId     操作ユーザーID
     * @return カテゴリレスポンス
     */
    public CategoryResponse getCategory(ScopeType scopeType, Long scopeId, Long categoryId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        BulletinCategoryEntity entity = findCategoryOrThrow(scopeType, scopeId, categoryId);
        return bulletinMapper.toCategoryResponse(entity);
    }

    /**
     * カテゴリを作成する。ADMIN or DEPUTY(MANAGE_CONTENT) のみ。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    作成者ID
     * @param request   作成リクエスト
     * @return 作成されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse createCategory(ScopeType scopeType, Long scopeId, Long userId, CreateCategoryRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

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
                .postMinRole(request.getPostMinRole() != null ? request.getPostMinRole() : DEFAULT_POST_MIN_ROLE)
                .createdBy(userId)
                .build();

        BulletinCategoryEntity saved = categoryRepository.save(entity);
        log.info("カテゴリ作成: scopeType={}, scopeId={}, categoryId={}", scopeType, scopeId, saved.getId());
        return bulletinMapper.toCategoryResponse(saved);
    }

    /**
     * カテゴリを更新する。ADMIN or DEPUTY(MANAGE_CONTENT) のみ。
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @param userId     操作ユーザーID
     * @param request    更新リクエスト
     * @return 更新されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse updateCategory(ScopeType scopeType, Long scopeId, Long categoryId, Long userId, UpdateCategoryRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

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
     * <p>設計書 F05.1 §5 に従い、配下のスレッドを巻き添え削除せず「未分類」
     * （{@code category_id = NULL}）へ移行してからカテゴリを論理削除する。
     * 未分類化 → 論理削除の順でトランザクション内に閉じて実行する。</p>
     *
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID
     * @param userId     操作ユーザーID
     * @return 削除結果（未分類へ移行したスレッド件数を含む）
     */
    @Transactional
    public DeleteCategoryResponse deleteCategory(ScopeType scopeType, Long scopeId, Long categoryId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

        BulletinCategoryEntity entity = findCategoryOrThrow(scopeType, scopeId, categoryId);

        // 1. 配下スレッドを未分類（category_id = NULL）へ退避（スレッドは削除しない）
        int affectedThreadCount = threadRepository.bulkSetCategoryIdNullByCategoryId(categoryId);

        // 2. カテゴリを論理削除
        entity.softDelete();
        categoryRepository.save(entity);

        log.info("カテゴリ削除: categoryId={}, 未分類化スレッド数={}", categoryId, affectedThreadCount);

        return new DeleteCategoryResponse(
                categoryId,
                affectedThreadCount,
                String.format("カテゴリを削除しました。%d件のスレッドが未分類に移行しました", affectedThreadCount));
    }

    /**
     * カテゴリエンティティを取得する。存在しない場合は例外をスローする。
     */
    BulletinCategoryEntity findCategoryOrThrow(ScopeType scopeType, Long scopeId, Long categoryId) {
        return categoryRepository.findByIdAndScopeTypeAndScopeId(categoryId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.CATEGORY_NOT_FOUND));
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — カテゴリ CRUD（グローバル経路）
    // ========================================================================

    /**
     * グローバル方式でカテゴリを作成する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は村モデレーター（HEADMAN/ELDER/SYSTEM_ADMIN）認可 + 村スコープ名称重複チェックを行い
     * {@code scope_village_id} 一致でカテゴリを作成する。ORG/TEAM/PERSONAL は既存 {@link #createCategory}
     * へ委譲する（管理権限要求は既存仕様どおり）。</p>
     *
     * @param scopeType      スコープ種別
     * @param scopeId        スコープ ID（VILLAGE 時は 0）
     * @param scopeVillageId 村 ID（VILLAGE 時必須・それ以外は無視）
     * @param userId         作成者 ID
     * @param request        作成リクエスト
     * @return 作成されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse createCategoryGlobal(ScopeType scopeType, Long scopeId, UUID scopeVillageId,
                                                 Long userId, CreateCategoryRequest request) {
        if (scopeType != ScopeType.VILLAGE) {
            return createCategory(scopeType, scopeId, userId, request);
        }
        if (scopeVillageId == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        villageBulletinAccessService.checkVillageBulletinModerator(scopeVillageId, userId);

        if (categoryRepository.existsByScopeVillageIdAndName(scopeVillageId, request.getName())) {
            throw new BusinessException(BulletinErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        BulletinCategoryEntity entity = BulletinCategoryEntity.builder()
                .scopeType(ScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(scopeVillageId)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 0)
                .color(request.getColor())
                .postMinRole(request.getPostMinRole() != null ? request.getPostMinRole() : DEFAULT_POST_MIN_ROLE)
                .createdBy(userId)
                .build();

        BulletinCategoryEntity saved = categoryRepository.save(entity);
        log.info("村カテゴリ作成: villageId={}, categoryId={}", scopeVillageId, saved.getId());
        return bulletinMapper.toCategoryResponse(saved);
    }

    /**
     * グローバル方式でカテゴリを更新する（F17.1 村掲示板グローバル方式）。
     *
     * <p>{@code categoryId} のみで叩かれるため、カテゴリの {@code scopeType} を逆引きして認可経路を分岐する。
     * VILLAGE は村モデレーター + 村スコープ名称重複チェック（自身除く）、ORG/TEAM/PERSONAL は
     * 既存 {@link #updateCategory} へ委譲する。</p>
     *
     * @param categoryId カテゴリ ID
     * @param userId     操作者 ID
     * @param request    更新リクエスト
     * @return 更新されたカテゴリレスポンス
     */
    @Transactional
    public CategoryResponse updateCategoryGlobal(Long categoryId, Long userId, UpdateCategoryRequest request) {
        BulletinCategoryEntity entity = findCategoryByIdOrThrow(categoryId);
        if (entity.getScopeType() != ScopeType.VILLAGE) {
            return updateCategory(entity.getScopeType(), entity.getScopeId(), categoryId, userId, request);
        }
        UUID villageId = entity.getScopeVillageId();
        villageBulletinAccessService.checkVillageBulletinModerator(villageId, userId);

        if (categoryRepository.existsByScopeVillageIdAndNameAndIdNot(villageId, request.getName(), categoryId)) {
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
        log.info("村カテゴリ更新: villageId={}, categoryId={}", villageId, categoryId);
        return bulletinMapper.toCategoryResponse(saved);
    }

    /**
     * グローバル方式でカテゴリを論理削除する（F17.1 村掲示板グローバル方式）。
     *
     * <p>VILLAGE は村モデレーター、ORG/TEAM/PERSONAL は既存 {@link #deleteCategory} へ委譲する。
     * いずれも配下スレッドを未分類（{@code category_id = NULL}）へ退避してからカテゴリを論理削除する
     * （巻き添え削除しない・設計書 F05.1 §5）。</p>
     *
     * @param categoryId カテゴリ ID
     * @param userId     操作者 ID
     * @return 削除結果（未分類へ移行したスレッド件数を含む）
     */
    @Transactional
    public DeleteCategoryResponse deleteCategoryGlobal(Long categoryId, Long userId) {
        BulletinCategoryEntity entity = findCategoryByIdOrThrow(categoryId);
        if (entity.getScopeType() != ScopeType.VILLAGE) {
            return deleteCategory(entity.getScopeType(), entity.getScopeId(), categoryId, userId);
        }
        villageBulletinAccessService.checkVillageBulletinModerator(entity.getScopeVillageId(), userId);

        // 配下スレッドを未分類（category_id = NULL）へ退避（スレッドは削除しない）
        int affectedThreadCount = threadRepository.bulkSetCategoryIdNullByCategoryId(categoryId);
        entity.softDelete();
        categoryRepository.save(entity);
        log.info("村カテゴリ削除: villageId={}, categoryId={}, 未分類化スレッド数={}",
                entity.getScopeVillageId(), categoryId, affectedThreadCount);
        return new DeleteCategoryResponse(
                categoryId,
                affectedThreadCount,
                String.format("カテゴリを削除しました。%d件のスレッドが未分類に移行しました", affectedThreadCount));
    }

    /**
     * カテゴリを ID のみで取得する（グローバル方式の逆引き）。存在しなければ 404。
     */
    private BulletinCategoryEntity findCategoryByIdOrThrow(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.CATEGORY_NOT_FOUND));
    }
}
