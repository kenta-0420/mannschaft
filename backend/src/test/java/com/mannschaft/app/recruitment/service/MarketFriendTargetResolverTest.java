package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * {@link MarketFriendTargetResolver} の単体テスト（宛先集合 UNION 解決・正規化キー検証）。
 *
 * <p>🔴-2 根治で {@link MarketFriendTargetService} から分離した純粋ロジック
 * （NotificationHelper 非依存）。Bean サイクル回避のための分離も含めて検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketFriendTargetResolver 単体テスト")
class MarketFriendTargetResolverTest {

    @Mock
    private RecruitmentFriendTargetRepository friendTargetRepository;
    @Mock
    private TeamFriendRepository teamFriendRepository;
    @Mock
    private TeamFriendFolderRepository folderRepository;
    @Mock
    private TeamFriendFolderMemberRepository folderMemberRepository;

    @InjectMocks
    private MarketFriendTargetResolver resolver;

    private static final Long OWNER_TEAM = 100L;
    private static final Long LISTING_ID = 5000L;

    @Test
    @DisplayName("ALL_FRIENDS + TEAM + FOLDER を集合和で解決し重複排除する")
    void resolvesUnionDeduplicated() {
        given(friendTargetRepository.findByListingId(LISTING_ID)).willReturn(List.of(
                RecruitmentFriendTargetEntity.ofAllFriends(LISTING_ID),
                RecruitmentFriendTargetEntity.ofTeam(LISTING_ID, 50L),
                RecruitmentFriendTargetEntity.ofFolder(LISTING_ID, 77L)));

        // ALL_FRIENDS 解決 → 50, 60（owner=100）
        given(teamFriendRepository.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                anyLong(), anyLong(), any()))
                .willReturn(List.of(friend(50L, 100L), friend(60L, 100L)));

        // TEAM(50) 再検証 → 成立
        given(teamFriendRepository.findByTeamAIdAndTeamBId(50L, 100L))
                .willReturn(Optional.of(friend(50L, 100L)));

        // FOLDER(77) 所有確認 OK + メンバー(team_friend_id=900 → 相手70)
        given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(77L, OWNER_TEAM))
                .willReturn(Optional.of(folder(77L, OWNER_TEAM)));
        given(folderMemberRepository.findByFolderId(77L))
                .willReturn(List.of(folderMember(900L)));
        given(teamFriendRepository.findAllById(any()))
                .willReturn(List.of(friendWithId(900L, 70L, 100L)));

        Set<Long> result = resolver.resolveTargetTeamIds(OWNER_TEAM, LISTING_ID);

        // 50（ALL+TEAM 重複排除）, 60（ALL）, 70（FOLDER）
        assertThat(result).containsExactlyInAnyOrder(50L, 60L, 70L);
    }

    @Test
    @DisplayName("宛先なし → 空集合")
    void noTargets_empty() {
        given(friendTargetRepository.findByListingId(LISTING_ID)).willReturn(List.of());
        assertThat(resolver.resolveTargetTeamIds(OWNER_TEAM, LISTING_ID)).isEmpty();
    }

    @Test
    @DisplayName("TEAM 宛先がフレンド解消済みなら集合に含めない")
    void resolvedTeam_unfriended_excluded() {
        given(friendTargetRepository.findByListingId(LISTING_ID)).willReturn(List.of(
                RecruitmentFriendTargetEntity.ofTeam(LISTING_ID, 50L)));
        given(teamFriendRepository.findByTeamAIdAndTeamBId(50L, 100L))
                .willReturn(Optional.empty()); // 解消済み

        assertThat(resolver.resolveTargetTeamIds(OWNER_TEAM, LISTING_ID)).isEmpty();
    }

    // ヘルパー

    private static TeamFriendEntity friend(Long a, Long b) {
        return TeamFriendEntity.builder()
                .teamAId(Math.min(a, b)).teamBId(Math.max(a, b))
                .aFollowId(1L).bFollowId(2L)
                .build();
    }

    private static TeamFriendEntity friendWithId(Long id, Long a, Long b) {
        TeamFriendEntity f = friend(a, b);
        setField(f, "id", id);
        return f;
    }

    private static TeamFriendFolderEntity folder(Long id, Long ownerTeamId) {
        TeamFriendFolderEntity f = TeamFriendFolderEntity.builder()
                .ownerTeamId(ownerTeamId)
                .name("テストフォルダ")
                .build();
        setField(f, "id", id);
        return f;
    }

    private static TeamFriendFolderMemberEntity folderMember(Long teamFriendId) {
        return TeamFriendFolderMemberEntity.builder()
                .folderId(77L)
                .teamFriendId(teamFriendId)
                .build();
    }

    private static void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("no field: " + name);
    }
}
