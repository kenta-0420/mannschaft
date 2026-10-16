package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 実認証主体を差し替え、実MVCとMySQLで他人の口座・申請・取消receiptへ到達できないことを固定する。
 * 対象は LoyaltyController#listAccounts / #myRequests / #myRequest / #cancel。
 * TEAM権限やQRの実業務検証はLoyaltyHttpContractITも担い、routing試練のFacade mockは利用しない。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoyaltyScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private LoyaltyStore store;
    @Autowired private Clock clock;
    private Long me;
    private Long other;
    private Long teamA;
    private Long teamB;
    private UUID mineAccount;
    private UUID otherAccount;
    private UUID mineRequest;
    private UUID otherRequest;
    private UUID cancelKey;
    private LocalDateTime now;

    @BeforeEach void fixture() {
        me = users.saveAndFlush(LoyaltyTestFixture.user()).getId();
        other = users.saveAndFlush(LoyaltyTestFixture.user()).getId();
        teamA = team("本人側");
        teamB = team("別チーム");
        now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).withNano(0);
        mineAccount = account(teamA, me, 7);
        otherAccount = account(teamB, other, 19);
        mineRequest = request(teamA, me, "ABCDEFGH", false);
        otherRequest = request(teamB, other, "BCDEFGHI", false);
    }

    @Test void listAccounts_同じURLで主語を差し替えると本人分だけ返る() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].accountId", containsInAnyOrder(mineAccount.toString())))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(other.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].accountId", containsInAnyOrder(otherAccount.toString())));
    }

    @Test void myRequests_所属や口座によらず本人申請だけ返る() throws Exception {
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].id", containsInAnyOrder(mineRequest.toString())));
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(other.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[*].id", containsInAnyOrder(otherRequest.toString())));
    }

    @Test void myRequest_同じ申請IDは所有者のみ取得できる() throws Exception {
        String path = "/api/v1/loyalty/award-requests/me/" + mineRequest;
        mvc.perform(get(path).with(user(me.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(mineRequest.toString()));
        mvc.perform(get(path).with(user(other.toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
    }

    @Test void cancel_他人は申請版やcodeや残高を更新できない() throws Exception {
        mvc.perform(post("/api/v1/loyalty/award-requests/me/" + mineRequest + "/cancel")
                        .with(user(other.toString())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
        var persisted = store.getRequests().findById(mineRequest).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(persisted.getVersion()).isZero();
        assertThat(persisted.getVerificationCode()).isEqualTo("ABCDEFGH");
        assertThat(store.getAccounts().findById(mineAccount).orElseThrow().getBalance()).isEqualTo(7);
    }

    @Test void cancel_本人の保存済receiptは外部状態を再検証せず同じ申請を返す() throws Exception {
        UUID closed = request(teamA, me, "CDEFGHIJ", true);
        String path = "/api/v1/loyalty/award-requests/me/" + closed + "/cancel";
        mvc.perform(post(path).with(user(me.toString())).header("Idempotency-Key", cancelKey)
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(closed.toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post(path).with(user(other.toString())).header("Idempotency-Key", cancelKey)
                        .contentType("application/json").content("{\"expectedRequestVersion\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
        assertThat(store.getRequests().findById(closed).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test void AC18_未認証の巨大本文は認証境界を通過しない() throws Exception {
        mvc.perform(post("/api/v1/loyalty/self-award-bootstrap/me")
                        .contentType("application/json").content("x".repeat(4097)))
                .andExpect(status().isUnauthorized());
    }

    private Long team(String label) {
        return teams.saveAndFlush(TeamEntity.builder().name(label)
                .slug("loyalty-" + UUID.randomUUID().toString().substring(0, 12))
                .visibility(TeamEntity.Visibility.PRIVATE).supporterEnabled(false).build()).getId();
    }
    private UUID account(Long team, Long owner, int balance) {
        return store.getAccounts().saveAndFlush(LoyaltyAccountEntity.builder().teamId(team).userId(owner)
                .balance(balance).lifetimeEarned(balance).createdAt(now).updatedAt(now).build()).getId();
    }
    private UUID request(Long team, Long owner, String code, boolean closed) {
        var rule = store.getRules().findByTeamId(team).orElseGet(() -> store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team)));
        UUID id = UuidV7.generate();
        var builder = LoyaltyAwardRequestEntity.builder().id(id).teamId(team).userId(owner).ruleId(rule.getId())
                .sourceLocationId(987654321L).programVersion(0).ruleVersion(0).points(1).ruleName("来店")
                .locationName("受付").awardOn(now.toLocalDate()).expiresAt(now.plusMinutes(10))
                .status(closed ? RequestStatus.CANCELLED : RequestStatus.PENDING).version(closed ? 1 : 0)
                .verificationCode(code).submitKey(UUID.randomUUID()).submitPayloadHash(new byte[32])
                .createdAt(now).updatedAt(now);
        if (closed) {
            cancelKey = UUID.randomUUID();
            builder.reason(RequestReason.USER_CANCELLED).terminalActorUserId(owner).terminalAction(RequestOperation.CANCEL)
                    .actionKey(cancelKey).actionPayloadHash(new LoyaltyPayloadHasher().action(RequestOperation.CANCEL,
                            team, id, new RequestActionRequest(0L))).closedAt(now);
        }
        return store.getRequests().saveAndFlush(builder.build()).getId();
    }
}
