package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F22.1 市: フレンド宛非公開札（{@code visibility='FRIEND_TEAMS_ONLY'}）の
 * 宛先集合を「現在の成立フレンド集合」へ都度解決する純粋ロジック（02_api_design §7）。
 *
 * <p><strong>循環参照の根治（第二陣 🔴-2）</strong>: 宛先解決は {@code NotificationHelper}
 * （→ {@code NotificationService} → {@code ContentVisibilityChecker} → 本解決を使う
 * {@link com.mannschaft.app.recruitment.visibility.RecruitmentListingVisibilityResolver}）に
 * 依存しないリポジトリのみで完結する。配信（通知送信）を持つ {@link MarketFriendTargetService}
 * から解決ロジックを分離し、Resolver が {@code NotificationHelper} 経由の Bean サイクルに
 * 巻き込まれないようにする。{@link MarketFriendTargetService} は本コンポーネントへ委譲する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFriendTargetResolver {

    private static final int FRIEND_RESOLVE_PAGE_SIZE = 10_000;

    private final RecruitmentFriendTargetRepository friendTargetRepository;
    private final TeamFriendRepository teamFriendRepository;
    private final TeamFriendFolderRepository folderRepository;
    private final TeamFriendFolderMemberRepository folderMemberRepository;

    /**
     * 札の宛先を「現在の成立フレンド集合」へ都度解決する（UNION・重複排除）。
     *
     * <p>{@code ALL_FRIENDS}/{@code FOLDER} はフレンド増減に追従して都度解決する。
     * 存在しないフォルダ・解消済みフレンドは空集合として安全に無視する。</p>
     *
     * @param ownerTeamId 札主チームID
     * @param listingId   札ID
     * @return 解決された宛先チームID集合（札主チーム自身は含めない）
     */
    public Set<Long> resolveTargetTeamIds(Long ownerTeamId, Long listingId) {
        Set<Long> result = new LinkedHashSet<>();
        List<RecruitmentFriendTargetEntity> targets = friendTargetRepository.findByListingId(listingId);
        if (targets.isEmpty()) {
            return result;
        }

        boolean needsAllFriends = targets.stream()
                .anyMatch(t -> t.getTargetKind() == RecruitmentFriendTargetKind.ALL_FRIENDS);
        if (needsAllFriends) {
            result.addAll(resolveAllFriends(ownerTeamId));
        }

        for (RecruitmentFriendTargetEntity target : targets) {
            switch (target.getTargetKind()) {
                case ALL_FRIENDS -> { /* 上で解決済み */ }
                case TEAM -> {
                    // 成立フレンドであることを再検証（解消後は無視）。
                    if (isStillFriend(ownerTeamId, target.getTeamId())) {
                        result.add(target.getTeamId());
                    }
                }
                case FOLDER -> result.addAll(resolveFolder(ownerTeamId, target.getFolderId()));
            }
        }
        result.remove(ownerTeamId);
        return result;
    }

    /** 札主チームの全成立フレンドチームID集合を解決する。 */
    private Set<Long> resolveAllFriends(Long ownerTeamId) {
        List<TeamFriendEntity> relations = teamFriendRepository
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                        ownerTeamId, ownerTeamId, PageRequest.of(0, FRIEND_RESOLVE_PAGE_SIZE));
        Set<Long> result = new LinkedHashSet<>();
        for (TeamFriendEntity rel : relations) {
            Long other = rel.getTeamAId().equals(ownerTeamId) ? rel.getTeamBId() : rel.getTeamAId();
            result.add(other);
        }
        return result;
    }

    /** 単一チームが今なお成立フレンドかを判定する（正規化キー検索）。 */
    public boolean isStillFriend(Long ownerTeamId, Long targetTeamId) {
        if (targetTeamId == null || targetTeamId.equals(ownerTeamId)) {
            return false;
        }
        Long teamA = Math.min(ownerTeamId, targetTeamId);
        Long teamB = Math.max(ownerTeamId, targetTeamId);
        return teamFriendRepository.findByTeamAIdAndTeamBId(teamA, teamB).isPresent();
    }

    /**
     * フォルダ内の成立フレンドチームID集合を解決する。
     * 存在しない / 他人所有フォルダは空集合として安全に無視する（症状を隠さずログ記録）。
     */
    private Set<Long> resolveFolder(Long ownerTeamId, Long folderId) {
        if (folderId == null) {
            return Set.of();
        }
        // フォルダが札主所有でない / 削除済みなら空集合（孤立対策・01_data_model §4）。
        if (folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(folderId, ownerTeamId).isEmpty()) {
            log.warn("F22.1 市: フォルダ宛先が不在 or 他人所有のため空集合扱い: folderId={}, ownerTeamId={}",
                    folderId, ownerTeamId);
            return Set.of();
        }
        List<TeamFriendFolderMemberEntity> members = folderMemberRepository.findByFolderId(folderId);
        if (members.isEmpty()) {
            return Set.of();
        }
        // team_friend_id → 相手チームID（成立フレンドのみ）。
        Set<Long> friendIds = members.stream()
                .map(TeamFriendFolderMemberEntity::getTeamFriendId)
                .collect(Collectors.toSet());
        Map<Long, TeamFriendEntity> relations = teamFriendRepository.findAllById(friendIds).stream()
                .collect(Collectors.toMap(TeamFriendEntity::getId, r -> r));
        Set<Long> result = new LinkedHashSet<>();
        for (Long friendId : friendIds) {
            TeamFriendEntity rel = relations.get(friendId);
            if (rel == null) {
                // フレンド解消済み（team_friends 物理削除）→ 安全に無視。
                continue;
            }
            Long other = rel.getTeamAId().equals(ownerTeamId) ? rel.getTeamBId() : rel.getTeamAId();
            result.add(other);
        }
        return result;
    }
}
