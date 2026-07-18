package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
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
    private final VillageMembershipRepository membershipRepository;
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

    /**
     * 村掲示板のモデレーション認可を検証する。認可違反時は例外を投げる（正常時は何も返さない）。
     *
     * <p>村掲示板グローバル方式（F17.1）の書込・モデレーション系操作（ピン留め・ロック・優先度変更・
     * 他者投稿の削除等）に対し、操作者が当該村のモデレーター（村ロール {@code HEADMAN} / {@code ELDER}）
     * または SYSTEM_ADMIN であることを要求する。村ロールの解決は village ドメインの
     * {@code village_memberships}（{@link VillageRole}）を正準とし、bulletin ドメインからは
     * 本メソッド経由で認可結果のみを受け取る（クロスドメイン原則1）。</p>
     *
     * <p>村存在性は閲覧認可と同様に削除／凍結済みを 404 として弾く（IDOR 対策で統一）。</p>
     *
     * @param villageId 対象村 ID（必須）
     * @param userId    操作しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない（{@link VillageErrorCode#VILLAGE_NOT_FOUND}）／
     *                           モデレーターでない
     *                           （{@link VillageErrorCode#VILLAGE_BULLETIN_MODERATE_FORBIDDEN}・403）
     */
    public void checkVillageBulletinModerator(UUID villageId, Long userId) {
        if (villageId == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        // 削除／凍結済みは 404（IDOR 対策で統一）
        villageRepository
                .findByIdAndDeletedAtIsNullAndArchivedAtIsNull(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));

        // SYSTEM_ADMIN は常に許可
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }

        // 村ロール HEADMAN / ELDER のみモデレーション可（村メンバーシップを正準解決）
        if (userId != null) {
            VillageRole role = membershipRepository
                    .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                            villageId, VillageSubjectType.USER, userId)
                    .filter(m -> m.getBannedAt() == null)
                    .map(VillageMembershipEntity::getRole)
                    .orElse(null);
            if (role == VillageRole.HEADMAN || role == VillageRole.ELDER) {
                return;
            }
        }
        throw new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
    }

    /**
     * 村ニュースレターの編集認可: <strong>現役</strong>の HEADMAN または ELDER 以外なら
     * {@link VillageErrorCode#MODERATION_FORBIDDEN}（403）を投げる（②-4 堅牢性 AC-15/16）。
     *
     * <p>従来 {@code VillageNewsletterService} と {@code VillageNewsletterIssueService} に
     * バイト同一の private 実装が二重に存在した（重複ロジック）。認可述語をこの一箇所へ寄せることで
     * 「呼び出し元まかせ・実装ドリフト」を構造的に防ぐ。「現役」の判定（退村 {@code leftAt} /
     * BAN {@code bannedAt} の除外）は正準クエリ {@code findActiveByVillageIdAndSubject} に委譲する。</p>
     *
     * <p>本メソッドは編集系の主体検証専用であり、村の存在確認は行わない（呼び出し元が閲覧認可
     * {@link #checkVillageBulletinViewAccess} や号ロードで村スコープを担保している）。</p>
     *
     * @param villageId   対象村 ID
     * @param actorUserId 操作しようとするユーザー ID
     * @throws BusinessException 現役 HEADMAN / ELDER でない場合（{@link VillageErrorCode#MODERATION_FORBIDDEN}）
     */
    public void requireHeadmanOrElder(UUID villageId, Long actorUserId) {
        VillageMembershipEntity actor = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, actorUserId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN));
        if (actor.getRole() != VillageRole.HEADMAN && actor.getRole() != VillageRole.ELDER) {
            throw new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN);
        }
    }
}
