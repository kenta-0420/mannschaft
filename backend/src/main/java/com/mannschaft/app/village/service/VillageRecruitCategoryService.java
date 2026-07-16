package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageRecruitCategoryCreateRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryOrderRequest;
import com.mannschaft.app.village.dto.VillageRecruitCategoryResponse;
import com.mannschaft.app.village.dto.VillageRecruitCategoryUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageRecruitCategoryEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRecruitCategoryRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 村ごと募集カテゴリマスタ Service（F17.1 P2）。
 *
 * <p>設計書 {@code docs/features/F17.1_village_headman_console_and_recruit_categories.md}
 * §6（API 設計）/ §9.1（受け入れ条件 AC-01〜17）に対応する。</p>
 *
 * <h2>認可（§6.3）</h2>
 * <p>CRUD は<strong>現役</strong>の村長（HEADMAN）または長老（ELDER）のみ（🔷マスター御裁可・Q1）。
 * 金型は {@code VillageReportService.requireModerator} 系統（BAN 安全）。「現役」の判定
 * （退村済み {@code leftAt} / BAN 済み {@code bannedAt} の除外）は
 * {@link VillageMembershipRepository#findActiveByVillageIdAndSubject} のクエリに一元化されており、
 * 本 Service はその述語を再利用するのみで独自の認可判定を書かない
 * （memory {@code project_matching_authz_userid_as_teamid_idor} 系の事故を避けるため）。</p>
 *
 * <p>一覧（読み取り）は村人（{@code isMember}）であれば足りる。役職は問わない
 * （HEADMAN/ELDER/VILLAGER/VISITOR いずれも可）。</p>
 *
 * <h2>IDOR 対策（§6.3）</h2>
 * <p>path の {@code villageId} と Entity の {@code villageId} が不一致の場合、および
 * 対象カテゴリが存在しない場合はいずれも {@code RECRUIT_CATEGORY_NOT_FOUND}（404）で統一する
 * （403 ではない。村ドメインの既存作法 — {@code VillageErrorCode} の VILLAGE_001 / VILLAGE_038 等
 * と同じ「IDOR 対策で 404 に統一」に揃える）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageRecruitCategoryService {

    /** 1村あたりのカテゴリ数上限（{@code TodoStatusLabelService.MAX_LABELS_PER_SCOPE} の先例に合わせる）。 */
    public static final int MAX_CATEGORIES_PER_VILLAGE = 20;

    /** 並び替え時の display_order 刻み幅（10刻み推奨・設計書 §4.2）。 */
    private static final int DISPLAY_ORDER_STEP = 10;

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageRecruitCategoryRepository categoryRepository;
    private final VillageMatchRecruitRepository recruitRepository;

    // ========================================================================
    // #1 GET .../recruit-categories — 一覧（村人）
    // ========================================================================

    /**
     * 村の生きているカテゴリ一覧を display_order 昇順で取得する（AC-04・AC-14c）。
     * 凍結村でも閲覧は許可する（§6.4）。
     */
    @Transactional(readOnly = true)
    public List<VillageRecruitCategoryResponse> list(UUID villageId, Long actorUserId) {
        loadVillage(villageId);
        requireVillager(villageId, actorUserId);

        List<VillageRecruitCategoryEntity> categories = categoryRepository
                .findByVillageIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAsc(villageId);
        Map<UUID, Long> counts = countsByCategory(villageId);

        return categories.stream()
                .map(c -> VillageRecruitCategoryResponse.of(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    // ========================================================================
    // #2 POST .../recruit-categories — 作成（HEADMAN or ELDER）
    // ========================================================================

    /**
     * 募集カテゴリを新規作成する（AC-01・AC-02・AC-06・AC-07・AC-08・AC-09）。
     */
    @Transactional
    public VillageRecruitCategoryResponse create(UUID villageId, Long actorUserId,
                                                 VillageRecruitCategoryCreateRequest request) {
        VillageEntity village = loadVillage(villageId);
        assertNotArchived(village);
        requireModerator(villageId, actorUserId);

        if (categoryRepository.existsActiveByVillageIdAndName(villageId, request.name())) {
            throw new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_NAME_DUPLICATED);
        }

        long count = categoryRepository.countByVillageIdAndDeletedAtIsNull(villageId);
        if (count >= MAX_CATEGORIES_PER_VILLAGE) {
            throw new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_LIMIT_EXCEEDED);
        }

        VillageRecruitCategoryEntity entity = VillageRecruitCategoryEntity.builder()
                .villageId(villageId)
                .name(request.name())
                .description(request.description())
                .color(request.color())
                .displayOrder(request.displayOrder() != null ? request.displayOrder() : 0)
                .isPreset(false)
                .presetKey(null)
                .createdBy(actorUserId)
                .build();
        entity = categoryRepository.save(entity);

        log.info("募集カテゴリ作成: villageId={}, categoryId={}, name={}, actor={}",
                villageId, entity.getId(), entity.getName(), actorUserId);
        return VillageRecruitCategoryResponse.of(entity, 0L);
    }

    // ========================================================================
    // #3 PUT .../recruit-categories/{id} — 更新（HEADMAN or ELDER）
    // ========================================================================

    /**
     * 募集カテゴリを更新する（部分更新。AC-06・AC-12・AC-13）。
     * {@code is_preset} のカテゴリも改名・削除できる（不変ではない。設計書 §4.2 の注）。
     */
    @Transactional
    public VillageRecruitCategoryResponse update(UUID villageId, UUID categoryId, Long actorUserId,
                                                 VillageRecruitCategoryUpdateRequest request) {
        VillageEntity village = loadVillage(villageId);
        assertNotArchived(village);
        requireModerator(villageId, actorUserId);

        VillageRecruitCategoryEntity entity = loadCategoryForVillage(villageId, categoryId);

        if (request.name() != null && !request.name().equals(entity.getName())) {
            if (categoryRepository.existsActiveByVillageIdAndNameExcludingId(villageId, request.name(), categoryId)) {
                throw new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_NAME_DUPLICATED);
            }
            entity.rename(request.name());
        }
        if (request.description() != null) {
            entity.redescribe(request.description());
        }
        if (request.color() != null) {
            entity.recolor(request.color());
        }
        if (request.displayOrder() != null) {
            entity.reorder(request.displayOrder());
        }

        entity = categoryRepository.save(entity);
        long recruitCount = recruitRepository.countByVillageIdAndCategoryIdAndDeletedAtIsNull(villageId, categoryId);

        log.info("募集カテゴリ更新: villageId={}, categoryId={}, actor={}", villageId, categoryId, actorUserId);
        return VillageRecruitCategoryResponse.of(entity, recruitCount);
    }

    // ========================================================================
    // #4 DELETE .../recruit-categories/{id} — 削除（HEADMAN or ELDER）
    // ========================================================================

    /**
     * 募集カテゴリを論理削除する。使用中（生きている募集が1件でも参照）なら
     * {@code RECRUIT_CATEGORY_IN_USE}（409・AC-10）。参照ゼロなら 204（AC-11）。
     */
    @Transactional
    public void delete(UUID villageId, UUID categoryId, Long actorUserId) {
        VillageEntity village = loadVillage(villageId);
        assertNotArchived(village);
        requireModerator(villageId, actorUserId);

        VillageRecruitCategoryEntity entity = loadCategoryForVillage(villageId, categoryId);

        long inUse = recruitRepository.countByVillageIdAndCategoryIdAndDeletedAtIsNull(villageId, categoryId);
        if (inUse > 0) {
            throw new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_IN_USE);
        }

        entity.softDelete();
        categoryRepository.save(entity);
        log.info("募集カテゴリ削除: villageId={}, categoryId={}, actor={}", villageId, categoryId, actorUserId);
    }

    // ========================================================================
    // #5 PUT .../recruit-categories/order — 一括並び替え（HEADMAN or ELDER）
    // ========================================================================

    /**
     * 指定された順序どおりに display_order を振り直す（10刻み・AC-14）。
     */
    @Transactional
    public List<VillageRecruitCategoryResponse> reorder(UUID villageId, Long actorUserId,
                                                        VillageRecruitCategoryOrderRequest request) {
        VillageEntity village = loadVillage(villageId);
        assertNotArchived(village);
        requireModerator(villageId, actorUserId);

        List<VillageRecruitCategoryEntity> reordered = new java.util.ArrayList<>();
        int order = DISPLAY_ORDER_STEP;
        for (UUID categoryId : request.orderedCategoryIds()) {
            VillageRecruitCategoryEntity entity = loadCategoryForVillage(villageId, categoryId);
            entity.reorder(order);
            order += DISPLAY_ORDER_STEP;
            reordered.add(categoryRepository.save(entity));
        }

        Map<UUID, Long> counts = countsByCategory(villageId);
        log.info("募集カテゴリ並び替え: villageId={}, count={}, actor={}",
                villageId, reordered.size(), actorUserId);
        return reordered.stream()
                .map(e -> VillageRecruitCategoryResponse.of(e, counts.getOrDefault(e.getId(), 0L)))
                .toList();
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除済みは VILLAGE_001）。凍結の可否はここでは判定しない（§6.4）。 */
    private VillageEntity loadVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return v;
    }

    /** 凍結済み村での書き込み操作を拒否する（§6.4・AC-15）。 */
    private void assertNotArchived(VillageEntity village) {
        if (village.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
    }

    /**
     * 実行者が当該村の<strong>現役</strong>村人であることを要求する（ロールは問わない・AC-04/AC-05）。
     * 「現役」の判定は {@code findActiveByVillageIdAndSubject} のクエリに委譲する。
     */
    private void requireVillager(UUID villageId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));
    }

    /**
     * 当該ユーザーが対象村の<strong>現役</strong>モデレーター（HEADMAN / ELDER）であることを要求する。
     * 一般村人・非村人・退村済み・BAN 済みは {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）。
     *
     * <p>BAN / 退村の検査は {@code findActiveByVillageIdAndSubject} のクエリに委譲する
     * （{@code VillageReportService.requireModerator} と同型・#2284 §12）。
     * 独自の {@code bannedAt != null} 手書き分岐は持たない — 5系統6実装に分裂した認可ヘルパーの
     * 轍を踏まないため（設計書 §3.4・§6.3）。</p>
     *
     * @return モデレーターのメンバーシップ Entity（将来の監査ログ用に返す）
     */
    private VillageMembershipEntity requireModerator(UUID villageId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        VillageMembershipEntity m = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
        return m;
    }

    /**
     * カテゴリを取得し、path の villageId と一致することを確認する（IDOR 対策・AC-12）。
     * 不一致・不存在いずれも 404 {@code RECRUIT_CATEGORY_NOT_FOUND} に統一する。
     */
    private VillageRecruitCategoryEntity loadCategoryForVillage(UUID villageId, UUID categoryId) {
        VillageRecruitCategoryEntity entity = categoryRepository.findByIdAndDeletedAtIsNull(categoryId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_NOT_FOUND));
        if (!entity.getVillageId().equals(villageId)) {
            throw new BusinessException(VillageErrorCode.RECRUIT_CATEGORY_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 村内のカテゴリ別・生きている募集件数を一括集計する（N+1 対策・設計書 §6.2）。
     */
    private Map<UUID, Long> countsByCategory(UUID villageId) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : recruitRepository.countActiveGroupedByCategory(villageId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }
}
