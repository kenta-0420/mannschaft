package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 村の主体（USER / TEAM / ORGANIZATION）として振る舞う権限を、取引の外で判定する段取り役。
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>村の参加・退出・参加申請はそれぞれ村ドメインの取引で行う。その取引の中から
 * {@link AccessControlService#isAdminOrAbove} を呼ぶと role ドメインの Repository へ推移的に到達し、
 * 取引が村ドメインの外へ越境する（D-3T 番人、CLAUDE.md DB 設計の原則 #5）。
 * 本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行い、村の取引へは
 * {@link VillageSubjectAuthorization}（判定結果）だけを渡す（{@code CorkboardScopedBoardVisibilityGuard} と同じ型）。</p>
 *
 * <h2>判定内容（従来の取引内判定と同じ）</h2>
 * <ul>
 *   <li>USER: 主体 ID が操作者本人であること（IDOR 対策）</li>
 *   <li>TEAM / ORGANIZATION: 操作者が当該スコープの ADMIN / DEPUTY_ADMIN であること</li>
 * </ul>
 *
 * <p>非公開村の可視性判定に使う SYSTEM_ADMIN 判定も、ここで取引の外に済ませて判定結果に載せる
 * （{@link VillageAccessGate#loadActiveVillage(java.util.UUID, Long, boolean)}）。</p>
 *
 * <p>本部品は例外を投げない。拒否は村の取引の中の従来の位置で {@link VillageSubjectAuthorization#require} が投げる。</p>
 */
@Component
@RequiredArgsConstructor
public class VillageSubjectAuthorizationGuard {

    private final AccessControlService accessControlService;
    private final VillageMembershipRepository membershipRepository;

    /** 指定した主体として振る舞えるかを判定する（参加・参加申請用）。 */
    public VillageSubjectAuthorization judge(Long actorUserId, VillageSubjectType subjectType, Long subjectId) {
        if (actorUserId == null || subjectType == null || subjectId == null) {
            return new VillageSubjectAuthorization(actorUserId, subjectType, subjectId, false);
        }
        boolean permitted = switch (subjectType) {
            case USER -> actorUserId.equals(subjectId);
            case TEAM -> accessControlService.isAdminOrAbove(actorUserId, subjectId, "TEAM");
            case ORGANIZATION -> accessControlService.isAdminOrAbove(actorUserId, subjectId, "ORGANIZATION");
        };
        return new VillageSubjectAuthorization(actorUserId, subjectType, subjectId, permitted,
                accessControlService.isSystemAdmin(actorUserId));
    }

    /**
     * 所属の主体として振る舞えるかを判定する（退出用）。
     *
     * <p>所属の主体（種別・ID）は作成後に変わらないため、取引の外で読んでも取引の中の判定と食い違わない。
     * 所属が無いときは常に拒否の判定を返す（不在の扱いは村の取引が従来どおり NOT_MEMBER で返す）。</p>
     */
    public VillageSubjectAuthorization judgeMembership(Long actorUserId, UUID membershipId) {
        if (membershipId == null || actorUserId == null) {
            return VillageSubjectAuthorization.unresolved(actorUserId, false);
        }
        return membershipRepository.findById(membershipId)
                .map(membership -> judge(actorUserId, membership.getSubjectType(), membership.getSubjectId()))
                .orElseGet(() -> VillageSubjectAuthorization.unresolved(
                        actorUserId, accessControlService.isSystemAdmin(actorUserId)));
    }
}
