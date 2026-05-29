package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 村掲示板グローバル方式（F17.1）の閲覧認可サービス。
 *
 * <p>村掲示板（カテゴリ／スレッド）の「閲覧（GET）」に対する認可を一元的に判定する。
 * 認可の根拠となる村メンバーシップ・村の {@code bulletin_visibility} は village ドメインの
 * 知識であるため、クロスドメイン原則（{@code backend/.claudecode.md} 原則1）に従い、
 * bulletin ドメインから village の {@link VillageEntity} を直接参照させず、本サービス経由で
 * 認可結果のみを返す。</p>
 *
 * <h2>認可ルール（設計書 F17.1 村掲示板グローバル方式 §2）</h2>
 * <ul>
 *   <li>村が存在しない／削除済／凍結済 → {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404、IDOR 対策）</li>
 *   <li>{@code bulletin_visibility = PUBLIC} → ログイン済ユーザーなら誰でも閲覧可（村メンバーでなくてよい）</li>
 *   <li>{@code bulletin_visibility = MEMBERS_ONLY} → 村メンバー または SYSTEM_ADMIN のみ閲覧可。
 *       非メンバーは {@link VillageErrorCode#VILLAGE_BULLETIN_VIEW_FORBIDDEN}（403）</li>
 * </ul>
 *
 * <p>未ログイン公開は本フェーズでは非対応（SecurityConfig は authenticated を維持）。
 * 本サービスは閲覧（GET）専用の認可であり、投稿系（作成・更新・削除）の主体検証は
 * {@link PostingIdentityService#validatePostingIdentity} が担う（責務分離）。</p>
 *
 * <h2>原則準拠</h2>
 * <p>読取専用ゆえ {@code @Transactional(readOnly=true)} に閉じる。クロスドメインの
 * Repository 呼び出しは {@link AccessControlService#isSystemAdmin}（SYSTEM_ADMIN 判定のみ）に
 * 限定し、副作用書き込みは行わない（原則5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageBulletinAccessService {

    private final VillageRepository villageRepository;
    private final PostingIdentityService postingIdentityService;
    private final AccessControlService accessControlService;

    /**
     * 村掲示板の閲覧認可を検証する。認可違反時は例外を投げる（正常時は何も返さない）。
     *
     * @param villageId 対象村 ID（必須）
     * @param userId    閲覧しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない（{@link VillageErrorCode#VILLAGE_NOT_FOUND}）／
     *                           MEMBERS_ONLY 村に非メンバーがアクセス
     *                           （{@link VillageErrorCode#VILLAGE_BULLETIN_VIEW_FORBIDDEN}）
     */
    public void checkVillageBulletinViewAccess(UUID villageId, Long userId) {
        if (villageId == null) {
            // scope_village_id 欠落は呼び出し側で 400 にすべきだが、防御的に 404 で弾く
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        // 削除／凍結済みは 404（IDOR 対策で統一）
        VillageEntity village = villageRepository
                .findByIdAndDeletedAtIsNullAndArchivedAtIsNull(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));

        VillageBulletinVisibility visibility = village.getBulletinVisibility();
        // 既存村で NULL の可能性に備え、安全側（MEMBERS_ONLY）にフォールバック
        if (visibility == null) {
            visibility = VillageBulletinVisibility.MEMBERS_ONLY;
        }

        if (visibility == VillageBulletinVisibility.PUBLIC) {
            // PUBLIC: ログイン済ユーザーなら誰でも閲覧可
            return;
        }

        // MEMBERS_ONLY: 村メンバー or SYSTEM_ADMIN のみ
        if (userId != null && postingIdentityService.isUserVillageMember(villageId, userId)) {
            return;
        }
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        throw new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);
    }
}
