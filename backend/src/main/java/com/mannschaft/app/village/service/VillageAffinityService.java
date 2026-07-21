package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.SharedVillagerBucket;
import com.mannschaft.app.village.dto.VillageAffinityResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F17.2 機能⑤ — 加入前相性表示サービス（設計書 §8）。
 *
 * <p>非メンバーが「この村は自分と合いそうか」を掴むための「相性のヒント」を、
 * 既存テーブル（村・メンバーシップ）の read 集約のみで組み立てる（新テーブル不要・§8）。</p>
 *
 * <h2>プライバシー（G4・§8.4）</h2>
 * <ul>
 *   <li>重なり人数は<strong>バケット化</strong>（HIDDEN/FEW/MANY）してのみ返す。正確人数・identity は返さない。</li>
 *   <li>相性クエリは監査記録（{@link AuditEventType#VILLAGE_AFFINITY_QUERIED}）し、差分攻撃を事後検知可能にする。</li>
 * </ul>
 *
 * <h2>認可（§8.7）</h2>
 * <ul>
 *   <li>{@code visibility=PUBLIC} の村のみ相性を返す（{@code join_policy} は不問＝FREE/APPROVAL とも開放）。</li>
 *   <li>{@code UNLISTED} 村は<strong>存在秘匿の 404</strong>（{@link VillageErrorCode#VILLAGE_NOT_FOUND}）で応答し、
 *       「存在するが非公開」を示唆しない（架空の村IDへの応答と区別がつかないようにする）。</li>
 *   <li>未ログインは Controller 入口の {@code SecurityUtils.getCurrentUserId()} が 401 を投げる。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageAffinityService {

    /** 匿名重なりバケット境界: FEW の下限（これ未満は HIDDEN）。 */
    private static final int BUCKET_FEW_MIN = 3;

    /** 匿名重なりバケット境界: MANY の下限。 */
    private static final int BUCKET_MANY_MIN = 10;

    /** 草分けアピール（§8.8）のしきい値: 総現役メンバーがこの値以下なら未参加者にアピール。 */
    private static final long PIONEER_MEMBER_THRESHOLD = 10L;

    /** 根拠一言の i18n キー（§8.5・巡礼推薦 reason と共有）。 */
    private static final String REASON_CATEGORY_MATCH = "village.affinity.reason.categoryMatch";
    private static final String REASON_SHARED_VILLAGERS = "village.affinity.reason.sharedVillagers";
    private static final String REASON_PIONEER = "village.affinity.reason.pioneer";

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    /**
     * 加入前相性表示を取得する（§8.3）。
     *
     * @param villageId 対象の村ID
     * @param viewerId  閲覧者（ログイン済ユーザー）ID
     * @return 相性のヒント（バケット化・i18n キー・アピールのみ。identity は含まない）
     */
    public VillageAffinityResponse getAffinity(UUID villageId, Long viewerId) {
        VillageEntity village = loadPublicVillageOrHide(villageId);

        // 閲覧者が対象村に現役参加済みか（草分けアピールは未参加者向け・§8.8）
        boolean viewerIsMember = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, viewerId)
                .isPresent();

        long memberCount = membershipRepository.countByVillageIdAndLeftAtIsNullAndBannedAtIsNull(villageId);

        boolean categoryMatch = computeCategoryMatch(village, viewerId);
        SharedVillagerBucket bucket = computeSharedVillagerBucket(villageId, viewerId);
        boolean pioneerAppeal = !viewerIsMember && memberCount <= PIONEER_MEMBER_THRESHOLD;

        List<String> reasonKeys = buildReasonKeys(categoryMatch, bucket, pioneerAppeal);

        // 監査（§8.4 緩和3・差分攻撃の事後検知用）。既存村サービスと同じ @Async record 方式。
        auditLogService.record(
                AuditEventType.VILLAGE_AFFINITY_QUERIED.name(),
                viewerId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"bucket\":\"" + bucket
                        + "\",\"categoryMatch\":" + categoryMatch
                        + ",\"pioneerAppeal\":" + pioneerAppeal + "}");

        return new VillageAffinityResponse(categoryMatch, bucket, reasonKeys, pioneerAppeal, memberCount);
    }

    // ====================================================================
    // 認可・存在秘匿
    // ====================================================================

    /**
     * PUBLIC 村を取得する。存在しない/削除済み/UNLISTED はすべて
     * {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404）で秘匿する（§8.7）。
     *
     * <p>UNLISTED を専用コードにせず 404 に寄せるのは、「架空の村IDへの応答」と
     * 「存在するが非公開の村への応答」の区別を攻撃者に与えないため（存在秘匿優先）。</p>
     */
    private VillageEntity loadPublicVillageOrHide(UUID villageId) {
        VillageEntity village = villageRepository.findById(villageId)
                .filter(v -> v.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (village.getVisibility() != VillageVisibility.PUBLIC) {
            // UNLISTED は存在秘匿（架空IDと同一の 404）。VILLAGE_099 は内部予約で通常返さない（§8.7）。
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return village;
    }

    // ====================================================================
    // 相性の各軸
    // ====================================================================

    /**
     * カテゴリ一致（§8.3）。閲覧者の「関心カテゴリ」＝閲覧者が現役所属する<strong>他の村</strong>の
     * カテゴリ集合とし、対象村のカテゴリがそこに含まれるかで判定する（新テーブル不要・read 集約）。
     */
    private boolean computeCategoryMatch(VillageEntity target, Long viewerId) {
        if (target.getCategory() == null || target.getCategory().isBlank()) {
            return false;
        }
        Set<UUID> viewerOtherVillageIds = viewerOtherActiveVillageIds(viewerId, target.getId());
        if (viewerOtherVillageIds.isEmpty()) {
            return false;
        }
        for (VillageEntity v : villageRepository.findAllById(viewerOtherVillageIds)) {
            if (v.getDeletedAt() == null && target.getCategory().equals(v.getCategory())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匿名重なりのバケット（§8.4・殿裁定の確定仕様）。
     *
     * <p>「対象村の<strong>現役</strong>村人（USER・left/banned NULL）のうち、閲覧者が<strong>現役</strong>所属する
     * <strong>他の村</strong>のいずれかにも現役所属している人数（distinct）」を重なり数とし、
     * {@code <3=HIDDEN / 3〜9=FEW / >=10=MANY} に丸める。正確人数は外へ出さない。</p>
     */
    private SharedVillagerBucket computeSharedVillagerBucket(UUID targetVillageId, Long viewerId) {
        List<Long> targetVillagers =
                membershipRepository.findActiveUserSubjectIdsByVillageId(targetVillageId);
        if (targetVillagers.isEmpty()) {
            return SharedVillagerBucket.HIDDEN;
        }

        // 閲覧者が現役所属する他村の現役村人集合（＝閲覧者と縁のある村人）
        Set<UUID> viewerOtherVillageIds = viewerOtherActiveVillageIds(viewerId, targetVillageId);
        Set<Long> coInhabitants = new HashSet<>();
        for (UUID otherVillageId : viewerOtherVillageIds) {
            coInhabitants.addAll(membershipRepository.findActiveUserSubjectIdsByVillageId(otherVillageId));
        }
        coInhabitants.remove(viewerId);

        Set<Long> counted = new HashSet<>();
        for (Long villager : targetVillagers) {
            if (!villager.equals(viewerId) && coInhabitants.contains(villager)) {
                counted.add(villager);
            }
        }
        return toBucket(counted.size());
    }

    private static SharedVillagerBucket toBucket(int overlap) {
        if (overlap < BUCKET_FEW_MIN) {
            return SharedVillagerBucket.HIDDEN;
        }
        if (overlap < BUCKET_MANY_MIN) {
            return SharedVillagerBucket.FEW;
        }
        return SharedVillagerBucket.MANY;
    }

    /**
     * 根拠一言 i18n キーの和集合（§8.5 真理値表）。
     *
     * <p>{@code HIDDEN} のときは {@code reason.sharedVillagers} を付けない
     * （重なりが秘匿されているのに「縁のある村人がいます」と示唆すると差分攻撃の手掛かりになる）。</p>
     */
    private static List<String> buildReasonKeys(boolean categoryMatch,
                                                SharedVillagerBucket bucket,
                                                boolean pioneerAppeal) {
        List<String> keys = new ArrayList<>(3);
        if (categoryMatch) {
            keys.add(REASON_CATEGORY_MATCH);
        }
        if (bucket == SharedVillagerBucket.FEW || bucket == SharedVillagerBucket.MANY) {
            keys.add(REASON_SHARED_VILLAGERS);
        }
        if (pioneerAppeal) {
            keys.add(REASON_PIONEER);
        }
        return keys;
    }

    /** 閲覧者が現役所属する村ID集合から対象村を除いたもの。 */
    private Set<UUID> viewerOtherActiveVillageIds(Long viewerId, UUID excludeVillageId) {
        Set<UUID> ids = new HashSet<>();
        for (VillageMembershipEntity m : membershipRepository.findActiveUserMemberships(viewerId)) {
            if (!m.getVillageId().equals(excludeVillageId)) {
                ids.add(m.getVillageId());
            }
        }
        return ids;
    }
}
