package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.ProfileVisibilityResponse;
import com.mannschaft.app.village.dto.UserVillageSummaryResponse;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F17.2 機能⑥ — 村人ミニプロフィールの所属村一覧サービス（設計書 §9）。
 *
 * <h2>公開トグル（§9.3）</h2>
 * <p>村人本人が「この村への所属を所属村一覧に公開してよいか」を切り替える。既定は非公開。</p>
 *
 * <h2>閲覧権限（§9.4・G4）</h2>
 * <ul>
 *   <li>閲覧者と対象者が<strong>少なくとも1村で現役同居</strong>していること（第一関門）。</li>
 *   <li>そのうえで対象者が {@code profile_public=TRUE} かつ 村 {@code visibility=PUBLIC} の村のみ返す（二重フィルタ）。</li>
 *   <li><strong>返せる村が0件のときは、共通村の有無に関わらず一律 403</strong>
 *       （{@link VillageErrorCode#PROFILE_VILLAGES_FORBIDDEN}）。200 空配列を返すと同居関係の有無を漏らすため（サイドチャネル）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageMembershipProfileService {

    private final VillageRepository villageRepository;
    private final VillageAccessGate accessGate;
    private final VillageMembershipRepository membershipRepository;
    private final MediaUrlResolver mediaUrlResolver;
    private final AuditLogService auditLogService;

    /**
     * 自分のその村所属の公開トグルを切り替える（本人のみ・§9.3）。
     *
     * <p>当該村に自分の現役メンバーシップが無い場合は {@link VillageErrorCode#NOT_MEMBER}（404・IDOR 秘匿）。
     * 他人の所属を操作する余地は無い（{@code me} スコープ＝呼び出し元の userId のみ対象）。</p>
     */
    @Transactional
    public ProfileVisibilityResponse updateMyProfileVisibility(UUID villageId, Long userId, boolean profilePublic) {
        // 村自体の存在確認・可視性判定は VillageAccessGate へ一元化する（削除済み・非可視は 404 秘匿）。
        // 凍結済み村でも公開トグルの切り替えは従来どおり許すため loadVillageAllowingArchived を使う。
        // 後段の NOT_MEMBER は VILLAGE_007 で不在の VILLAGE_001 と error.code が異なるため、
        // ゲートを通さないと「非公開村の非村人=VILLAGE_007 ／ 不在=VILLAGE_001」の差で村の実在が漏れていた。
        accessGate.loadVillageAllowingArchived(villageId, userId);

        VillageMembershipEntity membership = membershipRepository
                .findActiveByVillageIdAndSubject(villageId, VillageSubjectType.USER, userId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.NOT_MEMBER));

        membership.setProfilePublic(profilePublic);
        membershipRepository.save(membership);

        auditLogService.record(
                AuditEventType.VILLAGE_MEMBERSHIP_PROFILE_VISIBILITY_CHANGED.name(),
                userId, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId + "\",\"profilePublic\":" + profilePublic + "}");

        return new ProfileVisibilityResponse(villageId, profilePublic);
    }

    /**
     * 対象村人の所属村一覧を取得する（§9.3/§9.4）。
     *
     * @param targetUserId 対象村人の userId
     * @param viewerId     閲覧者の userId
     * @return 公開 ON かつ村 PUBLIC の所属村（村名・村紋・カテゴリ・村IDのみ）
     * @throws BusinessException 返せる村が0件のとき一律 {@link VillageErrorCode#PROFILE_VILLAGES_FORBIDDEN}（403）
     */
    public List<UserVillageSummaryResponse> getUserVillages(Long targetUserId, Long viewerId) {
        // 閲覧者の現役所属村ID集合
        Set<UUID> viewerVillageIds = new HashSet<>();
        for (VillageMembershipEntity m : membershipRepository.findActiveUserMemberships(viewerId)) {
            viewerVillageIds.add(m.getVillageId());
        }

        List<VillageMembershipEntity> targetMemberships =
                membershipRepository.findActiveUserMemberships(targetUserId);

        // 第一関門: 同居（少なくとも1村で現役同居）
        boolean cohabiting = targetMemberships.stream()
                .anyMatch(m -> viewerVillageIds.contains(m.getVillageId()));

        List<UserVillageSummaryResponse> result = new ArrayList<>();
        if (cohabiting) {
            // 二重フィルタ: 対象者が公開ON かつ 村 visibility=PUBLIC（削除済みは除外）
            List<UUID> publicOnVillageIds = targetMemberships.stream()
                    .filter(VillageMembershipEntity::isProfilePublic)
                    .map(VillageMembershipEntity::getVillageId)
                    .toList();
            if (!publicOnVillageIds.isEmpty()) {
                for (VillageEntity v : villageRepository.findAllById(publicOnVillageIds)) {
                    if (v.getDeletedAt() == null && v.getVisibility() == VillageVisibility.PUBLIC) {
                        result.add(new UserVillageSummaryResponse(
                                v.getId(),
                                v.getName(),
                                mediaUrlResolver.resolve(v.getMonshoR2Key()),
                                v.getCategory()));
                    }
                }
            }
        }

        // 0件は理由を問わず一律 403（同居関係の有無を漏らさない・§9.4）
        if (result.isEmpty()) {
            throw new BusinessException(VillageErrorCode.PROFILE_VILLAGES_FORBIDDEN);
        }

        result.sort(Comparator.comparing(UserVillageSummaryResponse::villageName,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }
}
