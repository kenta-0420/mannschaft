package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.FriendTargetRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * {@link MarketFriendTargetService} の単体テスト
 * （MARKET_002〜005・フレンド集合 UNION 解決・正規化キー検証）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketFriendTargetService 単体テスト")
class MarketFriendTargetServiceTest {

    @Mock
    private RecruitmentFriendTargetRepository friendTargetRepository;
    @Mock
    private TeamFriendRepository teamFriendRepository;
    @Mock
    private TeamFriendFolderRepository folderRepository;
    @Mock
    private TeamFriendFolderMemberRepository folderMemberRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private MarketFriendTargetService service;

    private static final Long OWNER_TEAM = 100L;
    private static final Long LISTING_ID = 5000L;

    // ════════════════════════════════════════════════════════════
    // validate — MARKET_002〜005
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("FRIEND_TEAMS_ONLY で friend_targets 0件 → MARKET_002")
        void friendOnly_empty_throwsMarket002() {
            assertThatThrownBy(() -> service.validate(
                    RecruitmentScopeType.TEAM, OWNER_TEAM, true, List.of(), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.FRIEND_TARGETS_REQUIRED);
        }

        @Test
        @DisplayName("FRIEND_TEAMS_ONLY × distribution_targets 併用 → MARKET_005")
        void friendOnly_withDistribution_throwsMarket005() {
            assertThatThrownBy(() -> service.validate(
                    RecruitmentScopeType.TEAM, OWNER_TEAM, true,
                    List.of(new FriendTargetRequest(RecruitmentFriendTargetKind.ALL_FRIENDS, null, null)),
                    List.of(com.mannschaft.app.recruitment.RecruitmentDistributionTargetType.PUBLIC_FEED)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.FRIEND_DISTRIBUTION_TARGETS_CONFLICT);
        }

        @Test
        @DisplayName("TEAM 宛先がフレンド未成立 → MARKET_003（正規化キー検索）")
        void team_notFriend_throwsMarket003() {
            // owner=100, target=50 → 正規化キー team_a=50, team_b=100
            given(teamFriendRepository.findByTeamAIdAndTeamBId(50L, 100L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.validate(
                    RecruitmentScopeType.TEAM, OWNER_TEAM, true,
                    List.of(new FriendTargetRequest(RecruitmentFriendTargetKind.TEAM, null, 50L)), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.FRIEND_NOT_ESTABLISHED);
        }

        @Test
        @DisplayName("TEAM 宛先がフレンド成立済み → 通過（正規化キー）")
        void team_isFriend_passes() {
            given(teamFriendRepository.findByTeamAIdAndTeamBId(50L, 100L))
                    .willReturn(Optional.of(friend(50L, 100L)));

            service.validate(RecruitmentScopeType.TEAM, OWNER_TEAM, true,
                    List.of(new FriendTargetRequest(RecruitmentFriendTargetKind.TEAM, null, 50L)), null);
            // 例外が出なければ OK
        }

        @Test
        @DisplayName("FOLDER 宛先が他チーム所有 → MARKET_004")
        void folder_notOwned_throwsMarket004() {
            given(folderRepository.findByIdAndOwnerTeamIdAndDeletedAtIsNull(77L, OWNER_TEAM))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.validate(
                    RecruitmentScopeType.TEAM, OWNER_TEAM, true,
                    List.of(new FriendTargetRequest(RecruitmentFriendTargetKind.FOLDER, 77L, null)), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.FOLDER_NOT_OWNED);
        }

        @Test
        @DisplayName("組織スコープで FRIEND_TEAMS_ONLY → MARKET_003（team_friends 非対応）")
        void orgScope_friendOnly_throws() {
            assertThatThrownBy(() -> service.validate(
                    RecruitmentScopeType.ORGANIZATION, OWNER_TEAM, true,
                    List.of(new FriendTargetRequest(RecruitmentFriendTargetKind.ALL_FRIENDS, null, null)), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.FRIEND_NOT_ESTABLISHED);
        }

        @Test
        @DisplayName("FRIEND_TEAMS_ONLY 以外は宛先・配信を無視（例外なし）")
        void notFriendOnly_skips() {
            service.validate(RecruitmentScopeType.TEAM, OWNER_TEAM, false, null,
                    List.of(com.mannschaft.app.recruitment.RecruitmentDistributionTargetType.PUBLIC_FEED));
        }
    }

    // ════════════════════════════════════════════════════════════
    // resolveTargetTeamIds — UNION 解決
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveTargetTeamIds（UNION 解決）")
    class Resolve {

        @Test
        @DisplayName("ALL_FRIENDS + TEAM + FOLDER を集合和で解決し重複排除する")
        void resolvesUnionDeduplicated() {
            // 宛先: ALL_FRIENDS / TEAM(50) / FOLDER(77)
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

            Set<Long> result = service.resolveTargetTeamIds(OWNER_TEAM, LISTING_ID);

            // 50（ALL+TEAM 重複排除）, 60（ALL）, 70（FOLDER）
            assertThat(result).containsExactlyInAnyOrder(50L, 60L, 70L);
        }

        @Test
        @DisplayName("宛先なし → 空集合")
        void noTargets_empty() {
            given(friendTargetRepository.findByListingId(LISTING_ID)).willReturn(List.of());
            assertThat(service.resolveTargetTeamIds(OWNER_TEAM, LISTING_ID)).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────────────────────

    private static TeamFriendEntity friend(Long a, Long b) {
        Long teamA = Math.min(a, b);
        Long teamB = Math.max(a, b);
        return TeamFriendEntity.builder()
                .teamAId(teamA).teamBId(teamB)
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
