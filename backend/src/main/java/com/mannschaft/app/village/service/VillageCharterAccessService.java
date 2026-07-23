package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 村憲章の <b>read 公開ゲート</b>（F17.3・設計書 §3.2）。
 *
 * <p>相性 API の主金型 {@code VillageAffinityService#loadPublicVillageOrHide}（PUBLIC のみ通し・
 * UNLISTED/削除/凍結は 404 秘匿）を土台に、UNLISTED 時のみ<b>現役メンバー/SYSTEM_ADMIN バイパス</b>
 * （掲示板 {@code checkVillageBulletinViewAccess} 由来）を足したハイブリッド。凍結村
 * （{@code archived_at} 非 NULL）も read では 404 に畳む（掲示板 read と同じ
 * {@code findByIdAndDeletedAtIsNullAndArchivedAtIsNull} 実体に揃える・§3.2）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageCharterAccessService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AccessControlService accessControlService;

    /**
     * 閲覧可能な村を返す。PUBLIC はログイン済なら誰でも、UNLISTED は現役メンバー/SYSTEM_ADMIN のみ、
     * それ以外（不存在・削除・凍結・UNLISTED 非メンバー）は {@code VILLAGE_NOT_FOUND}（404）で秘匿する。
     *
     * @param villageId 村 ID
     * @param viewerId  閲覧者ユーザー ID
     * @return 閲覧可能な村
     * @throws com.mannschaft.app.common.BusinessException 秘匿対象（不存在・削除・凍結・UNLISTED 非メンバー）は
     *                                                     {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404）
     */
    public VillageEntity loadReadableVillageOrHide(UUID villageId, Long viewerId) {
        // 削除済み・凍結済み・不存在は 404 に統一（掲示板 read と同じ実体に揃え archived も畳む・§3.2）。
        VillageEntity village = villageRepository
                .findByIdAndDeletedAtIsNullAndArchivedAtIsNull(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));

        // PUBLIC: ログイン済みなら誰でも（未参加者にも憲章を公開＝加入判断材料・§3.1）。
        if (village.getVisibility() == VillageVisibility.PUBLIC) {
            return village;
        }

        // UNLISTED: 現役メンバー（left_at/banned_at 除外）または SYSTEM_ADMIN のみ。
        if (viewerId != null && membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, viewerId)
                .isPresent()) {
            return village;
        }
        if (viewerId != null && accessControlService.isSystemAdmin(viewerId)) {
            return village;
        }
        // 非メンバーには存在ごと秘匿（架空 ID への応答と区別を与えない・§3.2）。
        throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
    }
}
