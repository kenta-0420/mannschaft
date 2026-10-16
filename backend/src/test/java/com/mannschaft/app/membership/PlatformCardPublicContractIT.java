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
 * 20同時確保はEntityのDDLではなく実Flyway由来のNULL一意制約を通す。
 * fixtureをcommitして独立writer TXから観測させ、所有行だけを後処理する。
 */
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none"})
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
    @Autowired private com.mannschaft.app.role.repository.RoleRepository roles;
    @Autowired private com.mannschaft.app.role.repository.UserRoleRepository userRoles;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final java.util.List<Long> ownedUsers = new java.util.ArrayList<>();
    private final java.util.List<Long> ownedTeams = new java.util.ArrayList<>();

    @Test
    void 未認証の確保は401() throws Exception {
        mvc.perform(post("/api/v1/member-cards/platform/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void active本人の再表示は同一カードで本人QRへ接続できる() throws Exception {
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
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
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void 初回確保を20同時実行してもNULLスコープは1枚だけ() throws Exception {
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(20)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return cardService.ensureMyPlatformCard(actor).getData().getMemberCardId();
                }));
            }
            start.countDown();
            var ids = new java.util.HashSet<Long>();
            for (var future : futures) {
                ids.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
            assertThat(cards.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(actor)).hasSize(1);
        }
    }

    @Test
    void inactive本人の確保と既存QRの他人取得は拒否する() throws Exception {
        Long frozen = saveUser(PlatformCardTestFixture.user().toBuilder().status(UserEntity.UserStatus.FROZEN).build()).getId();
        mvc.perform(post("/api/v1/member-cards/platform/me").with(user(frozen.toString())))
                .andExpect(status().isForbidden());
        assertThat(cards.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(frozen)).isEmpty();
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
        var card = cards.saveAndFlush(PlatformCardTestFixture.card(actor, ScopeType.PLATFORM, null, CardStatus.ACTIVE));
        assertThatThrownBy(() -> cardService.getQrToken(card.getId(), frozen)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 停止カードを確保で復活させず別カードも増やさない() {
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
        var card = cards.saveAndFlush(PlatformCardTestFixture.card(actor, ScopeType.PLATFORM, null, CardStatus.SUSPENDED));
        assertThatThrownBy(() -> cardService.ensureMyPlatformCard(actor)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_003);
        assertThat(cards.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(actor)).hasSize(1);
        assertThat(cards.findById(card.getId()).orElseThrow().getStatus()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void QR検証はチェックインと所属を書き込まない() {
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
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
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
        Long team = saveTeam(PlatformCardTestFixture.team()).getId();
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
        Long actor = saveUser(PlatformCardTestFixture.user()).getId();
        Long frozen = saveUser(PlatformCardTestFixture.user().toBuilder().status(UserEntity.UserStatus.FROZEN).build()).getId();
        Long team = saveTeam(PlatformCardTestFixture.team()).getId();
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

    @Test
    void 在籍snapshotはMEMBERと明示TEAM管理者を正式資格としglobal昇格を使わず定数SQLで読む() {
        Long team = saveTeam(PlatformCardTestFixture.team()).getId();
        Long member = saveUser(PlatformCardTestFixture.user()).getId();
        Long supporterAdmin = saveUser(PlatformCardTestFixture.user()).getId();
        Long guest = saveUser(PlatformCardTestFixture.user()).getId();
        Long systemAdmin = saveUser(PlatformCardTestFixture.user()).getId();
        for (Long id : List.of(member, supporterAdmin)) {
            memberships.saveAndFlush(MembershipEntity.builder().userId(id)
                    .scopeType(com.mannschaft.app.membership.domain.ScopeType.TEAM).scopeId(team)
                    .roleKind(id.equals(member) ? RoleKind.MEMBER : RoleKind.SUPPORTER).build());
        }
        userRoles.saveAndFlush(com.mannschaft.app.role.entity.UserRoleEntity.builder().userId(supporterAdmin)
                .teamId(team).roleId(roles.findByName("ADMIN").orElseThrow().getId()).build());
        userRoles.saveAndFlush(com.mannschaft.app.role.entity.UserRoleEntity.builder().userId(guest)
                .teamId(team).roleId(roles.findByName("GUEST").orElseThrow().getId()).build());
        userRoles.saveAndFlush(com.mannschaft.app.role.entity.UserRoleEntity.builder().userId(systemAdmin)
                .roleId(roles.findByName("SYSTEM_ADMIN").orElseThrow().getId()).build());
        var keys = List.of(member, supporterAdmin, guest, systemAdmin).stream()
                .map(id -> new AccessControlService.TeamUserKey(team, id)).toList();
        var stats = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        long before = stats.getPrepareStatementCount();
        try {
            var states = accessControl.getTeamMembershipStates(keys);
            assertThat(stats.getPrepareStatementCount() - before).isEqualTo(2);
            assertThat(states.get(keys.get(0))).isEqualTo(new AccessControlService.TeamMembershipState(true, true));
            assertThat(states.get(keys.get(1))).isEqualTo(new AccessControlService.TeamMembershipState(true, true));
            assertThat(states.get(keys.get(2))).isEqualTo(new AccessControlService.TeamMembershipState(false, false));
            assertThat(states.get(keys.get(3))).isEqualTo(new AccessControlService.TeamMembershipState(false, false));
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }
    private UserEntity saveUser(UserEntity entity) {
        UserEntity saved = users.saveAndFlush(entity);
        ownedUsers.add(saved.getId());
        return saved;
    }

    private com.mannschaft.app.team.entity.TeamEntity saveTeam(com.mannschaft.app.team.entity.TeamEntity entity) {
        var saved = teams.saveAndFlush(entity);
        ownedTeams.add(saved.getId());
        return saved;
    }

    @org.junit.jupiter.api.AfterEach
    void 所有fixtureだけを片付ける() {
        if (!ownedUsers.isEmpty()) {
            var params = java.util.Map.of("ids", ownedUsers);
            jdbc.update("DELETE FROM member_card_checkins WHERE member_card_id IN "
                    + "(SELECT id FROM member_cards WHERE user_id IN (:ids))", params);
            jdbc.update("DELETE FROM member_cards WHERE user_id IN (:ids)", params);
            jdbc.update("DELETE FROM checkin_locations WHERE created_by IN (:ids)", params);
            jdbc.update("DELETE FROM user_roles WHERE user_id IN (:ids)", params);
            jdbc.update("DELETE FROM memberships WHERE user_id IN (:ids)", params);
            jdbc.update("DELETE FROM users WHERE id IN (:ids)", params);
        }
        if (!ownedTeams.isEmpty()) {
            jdbc.update("DELETE FROM teams WHERE id IN (:ids)", java.util.Map.of("ids", ownedTeams));
        }
    }

}
