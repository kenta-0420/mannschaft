package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.service.MembershipStatsQueryService;
import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementRangeTemplateRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 実機E2E #4009 D-3: 告知の「チームを選ぶ」をチームの slug で受け取り、内部 ID へ写して検証する
 * （{@link BroadcastAudienceResolver}）。
 *
 * <p>FE は {@code GET /organizations/{slug}/teams} の {@code id}（= チームの slug）を送る。BE が {@code List<Long>} を
 * 期待していたため、チームにチェックした瞬間に宛先プレビューが 400 {@code COMMON_001} になっていた。
 * URL 識別子は slug 一本化の方針に合わせ、BE が slug を受けて解決する。存在しない slug・他組織のチーム・未加盟の
 * チームは区別せず同じ 400 {@code BROADCAST_002} にする（他組織のチームの存在を漏らさない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BroadcastAudienceResolver 「チームを選ぶ」の slug 解決（実機E2E #4009 D-3）")
class BroadcastAudienceResolverTeamSlugTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 20L;

    @Mock private AccessControlService accessControlService;
    @Mock private TeamOrgMembershipQueryService membershipQueryService;
    @Mock private OrgTeamGroupService orgTeamGroupService;
    @Mock private TeamService teamService;
    @Mock private MembershipStatsQueryService membershipStatsQueryService;
    @Mock private AnnouncementRangeTemplateRepository templateRepository;

    @InjectMocks
    private BroadcastAudienceResolver resolver;

    private static BroadcastAudienceSpec teams(List<String> slugs) {
        return new BroadcastAudienceSpec(slugs, null, null, null, null, "MEMBERS_AND_ABOVE");
    }

    private static String codeOf(Throwable e) {
        return ((BusinessException) e).getErrorCode().getCode();
    }

    @Test
    @DisplayName("組織に加盟しているチームの slug は内部 ID へ解決され、そのチームだけが宛先になる")
    void resolvesOwnTeamSlug() {
        given(teamService.findActiveTeamIdsBySlugs(List.of("t1-alpha"))).willReturn(Map.of("t1-alpha", 7L));
        given(membershipQueryService.findActiveTeamIdsIn(ORG_ID, List.of(7L))).willReturn(List.of(7L));

        ResolvedBroadcastAudience resolved = resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of("t1-alpha", "t1-alpha")), AnnouncementChannel.BULLETIN_THREAD);

        assertThat(resolved.mode()).isEqualTo(ResolvedBroadcastAudience.Mode.TEAMS);
        assertThat(resolved.targetTeamIds()).containsExactly(7L);
        assertThat(resolved.resolvedTeamIds()).containsExactly(7L);
    }

    @Test
    @DisplayName("存在しない slug は 400 BROADCAST_002（他組織・未加盟のチームと同じ応答）")
    void unknownSlugIs002() {
        given(teamService.findActiveTeamIdsBySlugs(List.of("no-such-team"))).willReturn(Map.of());

        assertThatThrownBy(() -> resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of("no-such-team")), AnnouncementChannel.BULLETIN_THREAD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_002"));
    }

    @Test
    @DisplayName("他組織のチームの slug（解決はできるが組織に加盟していない）も同じ 400 BROADCAST_002")
    void otherOrgTeamSlugIs002() {
        given(teamService.findActiveTeamIdsBySlugs(List.of("other-team"))).willReturn(Map.of("other-team", 99L));
        given(membershipQueryService.findActiveTeamIdsIn(ORG_ID, List.of(99L))).willReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of("other-team")), AnnouncementChannel.BULLETIN_THREAD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_002"));
    }

    @Test
    @DisplayName("非メンバーは slug を引く前に 403 COMMON_002（slug の解決結果で存在を推測させない）")
    void nonMemberIsRejectedBeforeSlugLookup() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of("t1-alpha")), AnnouncementChannel.BULLETIN_THREAD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("COMMON_002"));
        verifyNoInteractions(teamService, membershipQueryService);
    }

    @Test
    @DisplayName("空文字の slug は 400 BROADCAST_002、空配列は 400 BROADCAST_009（すべてのチームに倒さない）")
    void blankAndEmpty() {
        assertThatThrownBy(() -> resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of(" ")), AnnouncementChannel.BULLETIN_THREAD))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_002"));
        assertThatThrownBy(() -> resolver.resolveForBroadcast(
                USER_ID, "ORGANIZATION", ORG_ID, teams(List.of()), AnnouncementChannel.BULLETIN_THREAD))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_009"));
    }

    @Test
    @DisplayName("宛先プレビューも同じ検証（存在しない slug は 400 BROADCAST_002）")
    void previewUsesSameValidation() {
        given(teamService.findActiveTeamIdsBySlugs(List.of("no-such-team"))).willReturn(Map.of());
        var request = com.mannschaft.app.social.announcement.dto.AudiencePreviewRequestDto.builder()
                .targetRole("MEMBERS_AND_ABOVE").targetTeamIds(List.of("no-such-team")).build();

        assertThatThrownBy(() -> resolver.preview(USER_ID, ORG_ID, request))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_002"));
    }

    @Test
    @DisplayName("範囲テンプレート: slug を内部 ID へ写して保存する。null・空は全チーム（null）、未加盟は 400 BROADCAST_002")
    void templateTeams() {
        assertThat(resolver.resolveTemplateTeams(ORG_ID, null)).isNull();
        assertThat(resolver.resolveTemplateTeams(ORG_ID, List.of())).isNull();

        given(teamService.findActiveTeamIdsBySlugs(List.of("t1-alpha", "t2-beta")))
                .willReturn(Map.of("t1-alpha", 7L, "t2-beta", 8L));
        given(membershipQueryService.findActiveTeamIdsIn(ORG_ID, List.of(7L, 8L))).willReturn(List.of(8L, 7L));
        assertThat(resolver.resolveTemplateTeams(ORG_ID, List.of("t1-alpha", "t2-beta"))).containsExactly(7L, 8L);

        given(teamService.findActiveTeamIdsBySlugs(List.of("other-team"))).willReturn(Map.of("other-team", 99L));
        given(membershipQueryService.findActiveTeamIdsIn(ORG_ID, List.of(99L))).willReturn(List.of());
        assertThatThrownBy(() -> resolver.resolveTemplateTeams(ORG_ID, List.of("other-team")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("BROADCAST_002"));
    }

    @Test
    @DisplayName("応答用に内部 ID を slug へ写す（順序を保ち、引けないものは除く。null は null のまま）")
    void teamSlugsOf() {
        given(teamService.getSlugsByIds(List.of(8L, 7L, 5L))).willReturn(Map.of(7L, "t1-alpha", 8L, "t2-beta"));

        assertThat(resolver.teamSlugsOf(List.of(8L, 7L, 5L))).containsExactly("t2-beta", "t1-alpha");
        assertThat(resolver.teamSlugsOf(null)).isNull();
    }
}
