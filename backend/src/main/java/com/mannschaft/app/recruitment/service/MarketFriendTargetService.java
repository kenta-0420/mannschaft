package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.FriendTargetRequest;
import com.mannschaft.app.recruitment.dto.FriendTargetView;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F22.1 市: フレンド宛非公開札（{@code visibility='FRIEND_TEAMS_ONLY'}）の
 * 宛先検証・保存・集合解決・受信者解決を担当するサービス（02_api_design §4 / §7）。
 * 「届いた札」通知の配送は Issue #2997 で {@code RecruitmentNotificationListener}
 * （AFTER_COMMIT）へ移設した。本クラスは通知を作らず、受信者の解決だけを行う。
 *
 * <p>札主が <strong>TEAM スコープ</strong>のときのみ成立する（{@code team_friends} は team-to-team）。
 * 組織スコープの札にフレンド宛先は許可しない。</p>
 *
 * <p><strong>FK 方針</strong>: {@code recruitment_friend_targets} の {@code folder_id}/{@code team_id} は
 * クロスドメイン参照（F01.5/team）のため FK を張らず、本クラスで成立・所有を検証する
 * （CLAUDE.md 原則 1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketFriendTargetService {

    private final RecruitmentFriendTargetRepository friendTargetRepository;
    private final TeamFriendRepository teamFriendRepository;
    private final TeamFriendFolderRepository folderRepository;
    private final UserRoleRepository userRoleRepository;
    /** 宛先集合の解決（NotificationHelper 非依存・Bean サイクル回避）。 */
    private final MarketFriendTargetResolver friendTargetResolver;

    // =====================================================================
    // 検証
    // =====================================================================

    /**
     * 札立て / 編集時のフレンド宛先・配信対象の整合を検証する。
     *
     * <p>検証のみ（保存はしない）。{@code FRIEND_TEAMS_ONLY} 以外は宛先指定を無視する。</p>
     *
     * @param scopeType            札主スコープ種別
     * @param ownerTeamId          札主チームID（TEAM スコープのときの scopeId）
     * @param isFriendOnly         visibility が FRIEND_TEAMS_ONLY か
     * @param friendTargets        宛先リスト（null 可）
     * @param distributionTargets  配信対象リスト（null 可）
     * @throws BusinessException MARKET_002 / 003 / 004 / 005
     */
    public void validate(
            RecruitmentScopeType scopeType,
            Long ownerTeamId,
            boolean isFriendOnly,
            List<FriendTargetRequest> friendTargets,
            List<RecruitmentDistributionTargetType> distributionTargets) {

        if (!isFriendOnly) {
            // FRIEND_TEAMS_ONLY 以外では宛先指定は無視（保存もしない）。
            return;
        }

        // FRIEND_TEAMS_ONLY は TEAM スコープのみ（組織は team_friends を持たない）。
        if (scopeType != RecruitmentScopeType.TEAM) {
            throw new BusinessException(MarketErrorCode.FRIEND_NOT_ESTABLISHED);
        }

        // §4-3: distribution_targets 併用不可（MARKET_005）。
        if (distributionTargets != null && !distributionTargets.isEmpty()) {
            throw new BusinessException(MarketErrorCode.FRIEND_DISTRIBUTION_TARGETS_CONFLICT);
        }

        // §4-3: 0 件は MARKET_002。
        if (friendTargets == null || friendTargets.isEmpty()) {
            throw new BusinessException(MarketErrorCode.FRIEND_TARGETS_REQUIRED);
        }

        for (FriendTargetRequest target : friendTargets) {
            if (!target.isConsistent()) {
                // target_kind と参照列の不整合は入力バリデーション相当（MARKET_002 を流用）。
                throw new BusinessException(MarketErrorCode.FRIEND_TARGETS_REQUIRED);
            }
            switch (target.getTargetKind()) {
                case ALL_FRIENDS -> { /* 追加検証なし */ }
                case TEAM -> verifyFriendEstablished(ownerTeamId, target.getTeamId());
                case FOLDER -> verifyFolderOwnership(ownerTeamId, target.getFolderId());
            }
        }
    }

    /** {@code team_friends} 成立を正規化キー（team_a_id=MIN, team_b_id=MAX）で検証する（MARKET_003）。 */
    private void verifyFriendEstablished(Long ownerTeamId, Long targetTeamId) {
        if (targetTeamId == null || targetTeamId.equals(ownerTeamId)) {
            throw new BusinessException(MarketErrorCode.FRIEND_NOT_ESTABLISHED);
        }
        Long teamA = Math.min(ownerTeamId, targetTeamId);
        Long teamB = Math.max(ownerTeamId, targetTeamId);
        teamFriendRepository.findByTeamAIdAndTeamBId(teamA, teamB)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.FRIEND_NOT_ESTABLISHED));
    }

    /** フォルダが札主チームの所有であることを検証する（MARKET_004）。 */
    private void verifyFolderOwnership(Long ownerTeamId, Long folderId) {
        if (folderId == null) {
            throw new BusinessException(MarketErrorCode.FOLDER_NOT_OWNED);
        }
        folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, ownerTeamId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.FOLDER_NOT_OWNED));
    }

    // =====================================================================
    // 保存
    // =====================================================================

    /**
     * 札の宛先を再設定する（全削除→再INSERT）。検証済み前提で呼ぶこと。
     *
     * @param listingId     札ID
     * @param isFriendOnly  FRIEND_TEAMS_ONLY か（false なら全削除のみ）
     * @param friendTargets 宛先リスト
     */
    @Transactional
    public void replaceTargets(Long listingId, boolean isFriendOnly, List<FriendTargetRequest> friendTargets) {
        friendTargetRepository.deleteByListingId(listingId);
        if (!isFriendOnly || friendTargets == null || friendTargets.isEmpty()) {
            return;
        }
        // 重複排除（同一 kind+ref の重複指定を防ぐ）。
        Set<String> seen = new LinkedHashSet<>();
        for (FriendTargetRequest target : friendTargets) {
            String key = target.getTargetKind() + ":" + target.getFolderId() + ":" + target.getTeamId();
            if (!seen.add(key)) {
                continue;
            }
            RecruitmentFriendTargetEntity entity = switch (target.getTargetKind()) {
                case ALL_FRIENDS -> RecruitmentFriendTargetEntity.ofAllFriends(listingId);
                case FOLDER -> RecruitmentFriendTargetEntity.ofFolder(listingId, target.getFolderId());
                case TEAM -> RecruitmentFriendTargetEntity.ofTeam(listingId, target.getTeamId());
            };
            friendTargetRepository.save(entity);
        }
    }

    /**
     * 札の宛先ビューを取得する（札主 ADMIN 向けレスポンス用）。
     *
     * @param listingId 札ID
     * @return 宛先ビューリスト
     */
    public List<FriendTargetView> getTargetViews(Long listingId) {
        return friendTargetRepository.findByListingId(listingId).stream()
                .map(t -> new FriendTargetView(
                        t.getTargetKind().name(), t.getFolderId(), t.getTeamId()))
                .collect(Collectors.toList());
    }

    // =====================================================================
    // 集合解決（02_api_design §7）
    // =====================================================================

    /**
     * 札の宛先を「現在の成立フレンド集合」へ都度解決する（UNION・重複排除）。
     *
     * <p>{@code ALL_FRIENDS}/{@code FOLDER} はフレンド増減に追従して都度解決する。
     * 存在しないフォルダ・解消済みフレンドは空集合として安全に無視する。</p>
     *
     * @param ownerTeamId 札主チームID
     * @param listingId   札ID
     * @return 解決された宛先チームID集合
     */
    public Set<Long> resolveTargetTeamIds(Long ownerTeamId, Long listingId) {
        // 解決ロジックは NotificationHelper 非依存の Resolver に委譲する（Bean サイクル回避）。
        return friendTargetResolver.resolveTargetTeamIds(ownerTeamId, listingId);
    }

    // =====================================================================
    // 受信者解決（札立て時。配送は RecruitmentNotificationListener）
    // =====================================================================

    /**
     * 非公開札の宛先フレンドチーム管理者（ADMIN）のユーザーID集合を解決する（02_api_design §7）。
     *
     * <p>通知は作らない（Issue #2997）。呼び出し元（{@code RecruitmentListingService#publish}）が
     * 結果を {@code RecruitmentNotificationEvent} に載せ、コミット後に配送される。</p>
     *
     * @param ownerTeamId 札主チームID
     * @param listingId   札ID
     * @return 受信者ユーザーID集合（宛先チームなし・ADMIN 不在なら空）
     */
    public Set<Long> resolveFriendListingRecipientUserIds(Long ownerTeamId, Long listingId) {
        Set<Long> targetTeamIds = resolveTargetTeamIds(ownerTeamId, listingId);
        if (targetTeamIds.isEmpty()) {
            log.info("F22.1 市: フレンド宛非公開札の宛先チームなし: listingId={}", listingId);
            return Set.of();
        }
        // 各宛先チームの管理者（ADMIN）が受信者。
        Set<Long> recipientUserIds = new LinkedHashSet<>();
        for (Long teamId : targetTeamIds) {
            recipientUserIds.addAll(
                    userRoleRepository.findUserIdsByTeamIdAndRoleName(teamId, "ADMIN"));
        }
        if (recipientUserIds.isEmpty()) {
            log.info("F22.1 市: 宛先チームに ADMIN 不在のため通知対象なし: listingId={}", listingId);
        }
        return recipientUserIds;
    }
}
