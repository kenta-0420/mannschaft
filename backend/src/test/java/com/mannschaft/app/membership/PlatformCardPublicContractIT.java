package com.mannschaft.app.membership;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.membership.service.QrTokenService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.service.TeamService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F18.1 AC02/AC04/AC10の共通公開契約。
 * MemberCardController#ensureMyPlatformCard を実Security・実MySQLで検査する。
 * 共通QR検証・受付解決・状態投影はDBを更新しない。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PlatformCardPublicContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private MemberCardRepository cards;
    @Autowired private MemberCardCheckinRepository checkins;
    @Autowired private CheckinLocationRepository locations;
    @Autowired private MembershipRepository memberships;
    @Autowired private MemberCardService cardService;
    @Autowired private CheckinLocationService locationService;
    @Autowired private QrTokenService qrTokens;
    @Autowired private UserService userService;
    @Autowired private TeamService teamService;
    @Autowired private AccessControlService accessControl;

    @Test
    void 未認証の確保は401() throws Exception {
        mvc.perform(post("/api/v1/member-cards/platform/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void active本人の再表示は同一カードで本人QRへ接続できる() throws Exception {
        Long actor = users.saveAndFlush(PlatformCardTestFixture.user()).getId();
        mvc.perform(post("/api/v1/member-cards/platform/me").with(user(actor.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scopeType").value("PLATFORM"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        var first = cardService.ensureMyPlatformCard(actor).getData();
        var second = cardService.ensureMyPlatformCard(actor).getData();
        assertThat(second.getMemberCardId()).isEqualTo(first.getMemberCardId());
        var token = cardService.getQrToken(first.getMemberCardId(), actor).getData();
        assertThat(cardService.verifyPlatformQr(token.getQrToken()).userId()).isEqualTo(actor);
        assertThat(cards.findById(first.getMemberCardId()).orElseThrow().getScopeId()).isNull();
    }

    @Test
    void 停止カードを確保で復活させず別カードも増やさない() {
        Long actor = users.saveAndFlush(PlatformCardTestFixture.user()).getId();
        var card = cards.saveAndFlush(PlatformCardTestFixture.card(actor, ScopeType.PLATFORM, null, CardStatus.SUSPENDED));
        assertThatThrownBy(() -> cardService.ensureMyPlatformCard(actor)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_003);
        assertThat(cards.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(actor)).hasSize(1);
        assertThat(cards.findById(card.getId()).orElseThrow().getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void QR検証はチェックインと所属を書き込まない() {
        Long actor = users.saveAndFlush(PlatformCardTestFixture.user()).getId();
        var card = cards.saveAndFlush(PlatformCardTestFixture.card(actor, ScopeType.PLATFORM, null, CardStatus.ACTIVE));
        long beforeCheckins = checkins.count();
        long beforeMemberships = memberships.count();
        var verified = cardService.verifyPlatformQr(qrTokens.generateMemberCardQrToken(card.getCardCode(), card.getQrSecret()));
        assertThat(verified.memberCardId()).isEqualTo(card.getId());
        assertThat(checkins.count()).isEqualTo(beforeCheckins);
        assertThat(memberships.count()).isEqualTo(beforeMemberships);
        assertThat(cards.findById(card.getId()).orElseThrow().getCheckinCount()).isZero();
    }

    @Test
    void TEAM受付だけを副作用無しで解決する() {
        Long actor = users.saveAndFlush(PlatformCardTestFixture.user()).getId();
        Long team = teams.saveAndFlush(PlatformCardTestFixture.team()).getId();
        var location = locations.saveAndFlush(CheckinLocationEntity.builder().scopeType(ScopeType.TEAM).scopeId(team)
                .name("受付").locationCode(UUID.randomUUID().toString()).locationSecret(qrTokens.generateSecret())
                .isActive(true).autoCompleteReservation(true).createdBy(actor).build());
        long before = checkins.count();
        var result = locationService.resolveTeamLocationQr(qrTokens.generateLocationQrToken(location.getLocationCode(), location.getLocationSecret()));
        assertThat(result.locationId()).isEqualTo(location.getId());
        assertThat(result.teamId()).isEqualTo(team);
        assertThat(checkins.count()).isEqualTo(before);
        assertThat(locationService.getTeamLocationStates(List.of(location.getId())).get(location.getId()).active()).isTrue();
    }

    @Test
    void 状態バッチは所属種別を守り不明IDとinactiveのPIIを返さない() {
        Long actor = users.saveAndFlush(PlatformCardTestFixture.user()).getId();
        Long frozen = users.saveAndFlush(PlatformCardTestFixture.user().toBuilder().status(UserEntity.UserStatus.FROZEN).build()).getId();
        Long team = teams.saveAndFlush(PlatformCardTestFixture.team()).getId();
        memberships.saveAndFlush(MembershipEntity.builder().userId(actor)
                .scopeType(com.mannschaft.app.membership.domain.ScopeType.TEAM).scopeId(team).roleKind(RoleKind.SUPPORTER).build());
        var key = new AccessControlService.TeamUserKey(team, actor);
        var state = accessControl.getTeamMembershipStates(List.of(key)).get(key);
        assertThat(state.active()).isTrue();
        assertThat(state.regularMemberOrAbove()).isFalse();
        assertThat(userService.getActiveDisplayStates(List.of(actor)).get(actor).active()).isTrue();
        var frozenState = userService.getActiveDisplayStates(List.of(frozen)).get(frozen);
        assertThat(frozenState.active()).isFalse();
        assertThat(frozenState.displayName()).isNull();
        assertThat(userService.getActiveDisplayStates(List.of(Long.MAX_VALUE))).doesNotContainKey(Long.MAX_VALUE);
        assertThat(teamService.getActiveStates(List.of(team)).get(team).active()).isTrue();
        assertThat(teamService.getActiveStates(List.of(Long.MAX_VALUE))).doesNotContainKey(Long.MAX_VALUE);
    }
}
