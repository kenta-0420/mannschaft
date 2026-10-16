package com.mannschaft.app.loyalty;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import com.mannschaft.app.loyalty.entity.LoyaltyAccountEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyTransactionEntity;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.service.QrTokenService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.awaitility.Awaitility.await;

/** AC05/14/22/30のHTTP・実認可・実DB境界。Controllerの直呼びや内部Beanのmockは使わない。 */
@org.springframework.transaction.annotation.Transactional
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LoyaltyHttpContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private LoyaltyStore store;
    @Autowired private LoyaltyTime time;
    @Autowired private TeamRepository teams;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private CheckinLocationRepository locations;
    @Autowired private QrTokenService qr;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    private Long actorId;

    @BeforeEach
    void createActor() {
        UserEntity actor = LoyaltyTestFixture.user();
        actorId = users.saveAndFlush(actor).getId();
    }

    @Test
    void AC14_口座がなければ一覧は空でcursorメタを返す() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.hasNext").value(false)).andExpect(jsonPath("$.meta.limit").value(20));
    }

    @Test
    void AC22_口座も所属もなくても本人申請一覧は空() throws Exception {
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }

    @Test
    void AC14_未関係のチームへ口座直打ちしても存在情報は返さない() throws Exception {
        mvc.perform(get("/api/v1/teams/987654321/loyalty/account/me").with(user(actorId.toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("LOYALTY_001"));
    }

    @Test
    void AC05_当該TEAM権限がない操作スタッフは403() throws Exception {
        mvc.perform(get("/api/v1/teams/987654321/loyalty/operation-catalog").with(user(actorId.toString())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOYALTY_002"));
    }

    @Test
    void AC05_未認証は401() throws Exception {
        mvc.perform(get("/api/v1/loyalty/accounts/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void AC14_limitは1から100まで() throws Exception {
        for (String limit : new String[]{"0", "101"}) {
            mvc.perform(get("/api/v1/loyalty/accounts/me").param("limit", limit).with(user(actorId.toString())))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    @Test
    void AC17_bootstrapの不正QRは情報を返さず400() throws Exception {
        mvc.perform(post("/api/v1/loyalty/self-award-bootstrap/me").with(user(actorId.toString()))
                        .contentType("application/json").content("{\"locationToken\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("LOYALTY_010"));
    }

    @Test
    void 先行_AUTO成功receiptは停止後も不正QRを解析せず元取引を返す() throws Exception {
        Long team = team(); UUID key = UUID.randomUUID();
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var body = new SelfAwardRequest("invalid-location-token", rule.getId(), 0L, 0L);
        var saved = receipt(team, actorId, key, RequestOperation.SELF_SUBMIT, rule.getId(),
                new LoyaltyPayloadHasher().selfSubmit(team, body));
        var program = store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        program.update(false, Audience.MEMBERS_AND_ABOVE, SelfAwardMode.STAFF_APPROVAL, time.now());
        store.getPrograms().saveAndFlush(program);
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("AWARDED"))
                .andExpect(jsonPath("$.data.transaction.id").value(saved.getId().toString()));
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId).orElseThrow().getBalance()).isEqualTo(1);
    }

    @Test
    void 先行_申請成功receiptはモード変更と受付停止後も元終端申請を返す() throws Exception {
        Long team = team(); UUID key = UUID.randomUUID(); var now = time.now();
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var body = new SelfAwardRequest("invalid-location-token",rule.getId(),0L,0L);
        var row = LoyaltyAwardRequestEntity.builder().teamId(team).userId(actorId).ruleId(rule.getId())
                .sourceLocationId(Long.MAX_VALUE).programVersion(0).ruleVersion(0).points(1).ruleName("来店")
                .locationName("保存受付名").awardOn(now.toLocalDate()).expiresAt(time.expiresAt(now))
                .status(RequestStatus.PENDING).verificationCode("ABCDEFGH").submitKey(key)
                .submitPayloadHash(new LoyaltyPayloadHasher().selfSubmit(team,body)).createdAt(now).updatedAt(now).build();
        row.close(RequestStatus.CANCELLED,RequestReason.USER_CANCELLED,actorId,RequestOperation.CANCEL,
                UUID.randomUUID(),new byte[32],null,now);
        row = store.getRequests().saveAndFlush(row);
        var program = LoyaltyTestFixture.program(team); program.update(false,Audience.MEMBERS_AND_ABOVE,SelfAwardMode.AUTO,now);
        store.getPrograms().saveAndFlush(program);
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.request.id").value(row.getId().toString()))
                .andExpect(jsonPath("$.data.request.status").value("CANCELLED"));
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId)).isEmpty();
    }

    @Test
    void 先行_staff成功receiptは現権限を確認してからQRより先に回収する() throws Exception {
        Long team = team(); var grant = admin(actorId,team); UUID key = UUID.randomUUID();
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var body = new StaffAwardRequest("invalid-platform-qr",rule.getId(),0L,0L);
        var saved = receipt(team,actorId,key,RequestOperation.STAFF_AWARD,rule.getId(),new LoyaltyPayloadHasher().staffAward(team,body));
        String path = "/api/v1/teams/"+team+"/loyalty/awards";
        mvc.perform(post(path).with(user(actorId.toString())).header("Idempotency-Key",key)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(saved.getId().toString()));
        var changed = new StaffAwardRequest("different-invalid-qr",rule.getId(),0L,0L);
        mvc.perform(post(path).with(user(actorId.toString())).header("Idempotency-Key",key)
                        .contentType("application/json").content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOYALTY_006"));
        userRoles.delete(grant); userRoles.flush();
        mvc.perform(post(path).with(user(actorId.toString())).header("Idempotency-Key",key)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOYALTY_002"));
    }

    @Test
    void 先行_実受付bootstrapから申請し他スタッフ承認で初めて口座へ付与する() throws Exception {
        Long team = team(); Long staff = users.saveAndFlush(LoyaltyTestFixture.user()).getId(); admin(staff,team); admin(actorId,team);
        store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var location = locations.saveAndFlush(CheckinLocationEntity.builder()
                .scopeType(com.mannschaft.app.membership.ScopeType.TEAM).scopeId(team).name("受付")
                .locationCode(UUID.randomUUID().toString()).locationSecret(qr.generateSecret()).createdBy(staff).build());
        String token = qr.generateLocationQrToken(location.getLocationCode(),location.getLocationSecret());
        mvc.perform(post("/api/v1/loyalty/self-award-bootstrap/me").with(user(actorId.toString()))
                        .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("locationToken",token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.preview.teamId").value(team))
                .andExpect(jsonPath("$.data.account.balance").value(0));
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId)).isEmpty();
        var body = new SelfAwardRequest(token,rule.getId(),0L,0L);
        var response = mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.request.status").value("PENDING")).andReturn();
        var request = json.readTree(response.getResponse().getContentAsString()).path("data").path("request");
        String id = request.path("id").asText(); assertThat(id).isNotBlank();
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId)).isEmpty();
        String approve = json.writeValueAsString(java.util.Map.of("expectedRequestVersion",0,"verificationCode",request.path("verificationCode").asText()));
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/award-requests/"+id+"/approve")
                        .with(user(actorId.toString())).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(approve)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/award-requests/"+id+"/approve")
                        .with(user(staff.toString())).header("Idempotency-Key",UUID.randomUUID())
                        .contentType("application/json").content(approve))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balanceAfter").value(1));
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(store.getRequests().findById(UUID.fromString(id)).orElseThrow().getStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void 先行_AC25旧受付停止後は別の有効受付で旧申請を終端化して再申請できる() throws Exception {
        Long team = team(); store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var oldLocation = location(team,false); var activeLocation = location(team,true);
        var now = time.now();
        var old = store.getRequests().saveAndFlush(LoyaltyAwardRequestEntity.builder().teamId(team).userId(actorId)
                .ruleId(rule.getId()).sourceLocationId(oldLocation.getId()).programVersion(0).ruleVersion(0).points(1)
                .ruleName("来店").locationName("旧受付").awardOn(now.toLocalDate()).expiresAt(time.expiresAt(now))
                .status(RequestStatus.PENDING).verificationCode("ABCDEFGH").submitKey(UUID.randomUUID())
                .submitPayloadHash(new byte[32]).createdAt(now).updatedAt(now).build());
        String token = qr.generateLocationQrToken(activeLocation.getLocationCode(),activeLocation.getLocationSecret());
        var response = mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content(json.writeValueAsString(new SelfAwardRequest(token,rule.getId(),0L,0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.request.status").value("PENDING")).andReturn();
        String next = json.readTree(response.getResponse().getContentAsString()).path("data").path("request").path("id").asText();
        assertThat(next).isNotBlank().isNotEqualTo(old.getId().toString());
        var closed = store.getRequests().findById(old.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(RequestStatus.INVALIDATED);
        assertThat(closed.getReason()).isEqualTo(RequestReason.LOCATION_INACTIVE);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId)).isEmpty();
    }

    @Test
    void 先行_有効な同日PENDINGへ別キー送信しても元申請200へcoalesceしない() throws Exception {
        Long team = team(); store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team)); var location = location(team,true);
        var now = time.now();
        var old = store.getRequests().saveAndFlush(LoyaltyAwardRequestEntity.builder().teamId(team).userId(actorId)
                .ruleId(rule.getId()).sourceLocationId(location.getId()).programVersion(0).ruleVersion(0).points(1)
                .ruleName("来店").locationName("受付").awardOn(now.toLocalDate()).expiresAt(time.expiresAt(now))
                .status(RequestStatus.PENDING).verificationCode("ABCDEFGH").submitKey(UUID.randomUUID())
                .submitPayloadHash(new byte[32]).createdAt(now).updatedAt(now).build());
        String token = qr.generateLocationQrToken(location.getLocationCode(),location.getLocationSecret());
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content(json.writeValueAsString(new SelfAwardRequest(token,rule.getId(),0L,0L))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("LOYALTY_013"));
        assertThat(store.getRequests().findById(old.getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 先行_監査は実afterCommitで最小metadataだけ保存しrollback時には保存しない() {
        Long team = Math.abs(UUID.randomUUID().getLeastSignificantBits()%1_000_000_000L)+1_000_000_000L;
        UUID transaction = UUID.randomUUID(); UUID request = UUID.randomUUID();
        var event = new LoyaltyCommittedEvent(team,actorId,actorId,RequestOperation.APPROVE,transaction,request);
        var tx = new TransactionTemplate(transactionManager);
        try {
            tx.executeWithoutResult(status -> { events.publishEvent(event); status.setRollbackOnly(); });
            assertThat(audits()).isZero();
            tx.executeWithoutResult(status -> events.publishEvent(event));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(audits()).isEqualTo(1L));
            String metadata = jdbc.queryForObject("SELECT metadata FROM audit_logs WHERE user_id=? AND event_type=?",
                    String.class,actorId,LoyaltyCommittedEvent.AUDIT_EVENT_TYPE);
            var node = json.readTree(metadata);
            assertThat(node.size()).isEqualTo(3);
            assertThat(node.path("operation").asText()).isEqualTo("APPROVE");
            assertThat(node.path("transactionId").asText()).isEqualTo(transaction.toString());
            assertThat(node.path("requestId").asText()).isEqualTo(request.toString());
        } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        finally {
            jdbc.update("DELETE FROM audit_logs WHERE user_id=? AND event_type=?",actorId,LoyaltyCommittedEvent.AUDIT_EVENT_TYPE);
            users.deleteById(actorId);
        }
    }

    private Long audits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND event_type=?",Long.class,
                actorId,LoyaltyCommittedEvent.AUDIT_EVENT_TYPE);
    }
    private CheckinLocationEntity location(Long team, boolean active) {
        return locations.saveAndFlush(CheckinLocationEntity.builder().scopeType(com.mannschaft.app.membership.ScopeType.TEAM)
                .scopeId(team).name("受付").locationCode(UUID.randomUUID().toString()).locationSecret(qr.generateSecret())
                .isActive(active).createdBy(actorId).build());
    }
    private Long team() {
        return teams.saveAndFlush(TeamEntity.builder().name("HTTP試験").slug("loyalty-http-"+UUID.randomUUID().toString().substring(0,12))
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build()).getId();
    }
    private UserRoleEntity admin(Long user, Long team) {
        var role = roles.findByName("ADMIN").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder()
                .name("ADMIN").displayName("管理者").priority(100).isSystem(true).build()));
        return userRoles.saveAndFlush(UserRoleEntity.builder().userId(user).roleId(role.getId()).teamId(team).grantedBy(user).build());
    }
    private LoyaltyTransactionEntity receipt(Long team, Long user, UUID key, RequestOperation operation, UUID rule, byte[] hash) {
        var now = time.now();
        var account = store.getAccounts().saveAndFlush(LoyaltyAccountEntity.builder().teamId(team).userId(user)
                .balance(1).lifetimeEarned(1).createdAt(now).updatedAt(now).build());
        return store.getTransactions().saveAndFlush(LoyaltyTransactionEntity.builder().teamId(team).userId(user).actorUserId(actorId)
                .accountId(account.getId()).kind(TransactionKind.AWARD)
                .channel(operation==RequestOperation.SELF_SUBMIT?TransactionChannel.SELF_AUTO:TransactionChannel.STAFF)
                .requestOperation(operation).delta(1).balanceAfter(1).ruleId(rule).awardOn(now.toLocalDate())
                .sourceLocationId(operation==RequestOperation.SELF_SUBMIT?Long.MAX_VALUE:null).label("来店")
                .ruleOrRewardVersion(0).programVersion(0).requestKey(key).payloadHash(hash).createdAt(now).updatedAt(now).build());
    }
}
