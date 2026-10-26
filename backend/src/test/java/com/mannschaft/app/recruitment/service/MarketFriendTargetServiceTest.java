package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.FriendTargetRequest;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link MarketFriendTargetService} の単体テスト（MARKET_002〜005・正規化キー検証）。
 *
 * <p>宛先集合 UNION 解決ロジックは {@link MarketFriendTargetResolver} に分離したため
 * {@code MarketFriendTargetResolverTest} で検証する（本テストは validate のみ）。</p>
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
    private UserRoleRepository userRoleRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private MarketFriendTargetResolver friendTargetResolver;

    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private MessageSource messageSource;

    @InjectMocks
    private MarketFriendTargetService service;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private static final Long OWNER_TEAM = 100L;

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
}
