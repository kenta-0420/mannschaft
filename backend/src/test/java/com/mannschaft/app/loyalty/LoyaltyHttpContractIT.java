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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
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
    @Autowired private LoyaltyMapper mapper;
    @Autowired private LoyaltyRequestStatePolicy requestStates;
    @Autowired private com.mannschaft.app.membership.repository.MembershipRepository memberships;
    @Autowired private com.mannschaft.app.membership.repository.MemberCardRepository memberCards;
    @Autowired private com.mannschaft.app.membership.repository.MemberCardCheckinRepository checkins;
    @Autowired private com.mannschaft.app.role.repository.PermissionRepository permissions;
    @Autowired private com.mannschaft.app.role.repository.PermissionGroupRepository permissionGroups;
    @Autowired private com.mannschaft.app.role.repository.PermissionGroupPermissionRepository groupPermissions;
    @Autowired private com.mannschaft.app.role.repository.UserPermissionGroupRepository userGroups;
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
    void 先行_AC25TEAM停止後も現スタッフ権限で本人とALLと詳細の無効理由を読める() throws Exception {
        Long team = team(); admin(actorId,team);
        store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team)); var location = location(team,true);
        var now = time.now();
        var row = store.getRequests().saveAndFlush(LoyaltyAwardRequestEntity.builder().teamId(team).userId(actorId)
                .ruleId(rule.getId()).sourceLocationId(location.getId()).programVersion(0).ruleVersion(0).points(1)
                .ruleName("来店").locationName("受付").awardOn(now.toLocalDate()).expiresAt(time.expiresAt(now))
                .status(RequestStatus.PENDING).verificationCode("ABCDEFGH").submitKey(UUID.randomUUID())
                .submitPayloadHash(new byte[32]).createdAt(now).updatedAt(now).build());
        var stopped = teams.findById(team).orElseThrow(); stopped.archive(); teams.saveAndFlush(stopped);
        mvc.perform(get("/api/v1/loyalty/award-requests/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reason").value("TEAM_INACTIVE"));
        mvc.perform(get("/api/v1/loyalty/award-requests/"+row.getId()+"/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVALIDATED"))
                .andExpect(jsonPath("$.data.reason").value("TEAM_INACTIVE"));
        mvc.perform(get("/api/v1/teams/"+team+"/loyalty/award-requests").param("status","ALL")
                        .with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reason").value("TEAM_INACTIVE"));
        mvc.perform(get("/api/v1/teams/"+team+"/loyalty/award-requests/"+row.getId()).with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("TEAM_INACTIVE"));
        assertThat(store.getRequests().findById(row.getId()).orElseThrow().getStatus()).isEqualTo(RequestStatus.PENDING);
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

    @Test
    void 補強_AC09本人付与の混入userIdとpointsとawardOnを採用しない() throws Exception {
        Long team = team(); Long other = users.saveAndFlush(LoyaltyTestFixture.user()).getId();
        var program = LoyaltyTestFixture.program(team);
        program.update(true, Audience.ALL_AUTHENTICATED, SelfAwardMode.AUTO, time.now());
        store.getPrograms().saveAndFlush(program);
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team)); var location = location(team,true);
        var body = new java.util.LinkedHashMap<String,Object>();
        body.put("locationToken",qr.generateLocationQrToken(location.getLocationCode(),location.getLocationSecret()));
        body.put("ruleId",rule.getId()); body.put("expectedRuleVersion",0); body.put("expectedProgramVersion",1);
        body.put("userId",other); body.put("points",9999); body.put("awardOn","1900-01-01");
        var response = mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("AWARDED"))
                .andExpect(jsonPath("$.data.transaction.delta").value(1))
                .andExpect(jsonPath("$.data.transaction.awardOn").value(time.today().toString())).andReturn();
        UUID id = UUID.fromString(json.readTree(response.getResponse().getContentAsString()).path("data").path("transaction").path("id").asText());
        var saved = store.getTransactions().findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(actorId);
        assertThat(saved.getDelta()).isEqualTo(rule.getPoints());
        assertThat(saved.getAwardOn()).isEqualTo(time.today());
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,actorId).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(store.getAccounts().findByTeamIdAndUserId(team,other)).isEmpty();
    }

    @Test
    void 補強_固定ClockのJST日付とUTC保存値が本人HTTPのISO日時を往復する() throws Exception {
        Long team = team();
        store.getPrograms().saveAndFlush(LoyaltyTestFixture.program(team));
        var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        Instant instant = Instant.parse("2026-10-16T15:00:00Z");
        var fixedTime = new LoyaltyTime(Clock.fixed(instant,ZoneOffset.UTC));
        var actualWriter = new LoyaltyPointsWriter(store,fixedTime,mapper,requestStates,events,transactionManager);
        var saved = actualWriter.award(new LoyaltyWriteCommand(team,actorId,actorId,rule.getId(),0L,0L,
                RequestOperation.STAFF_AWARD,UUID.randomUUID(),new byte[32],null,null));
        var result = mvc.perform(get("/api/v1/teams/"+team+"/loyalty/transactions/me").with(user(actorId.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(saved.id().toString()))
                .andExpect(jsonPath("$.data[0].awardOn").value("2026-10-17")).andReturn();
        var node = json.readTree(result.getResponse().getContentAsString()).path("data").get(0);
        assertThat(OffsetDateTime.parse(node.path("createdAt").asText()).toInstant()).isEqualTo(instant);
        var decoded = json.treeToValue(node,com.mannschaft.app.loyalty.dto.TransactionResponse.class);
        assertThat(decoded.createdAt()).isEqualTo(LocalDateTime.of(2026,10,17,0,0));
        String raw = jdbc.queryForObject("SELECT CAST(created_at AS CHAR) FROM team_loyalty_transactions WHERE team_id=? AND id=?",
                String.class,team,saved.id().toString());
        assertThat(LocalDateTime.parse(raw.replace(' ','T'))).isEqualTo(LocalDateTime.of(2026,10,16,15,0));
    }

    @Test
    void 補強_AC03TEAM別残高は独立し既存所属とカードとcheckinを変更しない() throws Exception {
        Long a = team(); Long b = team();
        var membership = memberships.saveAndFlush(com.mannschaft.app.membership.entity.MembershipEntity.builder()
                .userId(actorId).scopeType(com.mannschaft.app.membership.domain.ScopeType.TEAM).scopeId(a)
                .roleKind(com.mannschaft.app.membership.domain.RoleKind.SUPPORTER).joinedAt(time.now()).invitedBy(actorId).build());
        var card = memberCards.saveAndFlush(com.mannschaft.app.membership.entity.MemberCardEntity.builder()
                .userId(actorId).scopeType(com.mannschaft.app.membership.ScopeType.TEAM).scopeId(a)
                .cardCode(UUID.randomUUID().toString()).cardNumber(UUID.randomUUID().toString().substring(0,16))
                .displayName("既存カード").qrSecret(qr.generateSecret()).checkinCount(5)
                .totalSpend(new java.math.BigDecimal("99.50")).build());
        var oldCheckin = checkins.saveAndFlush(com.mannschaft.app.membership.entity.MemberCardCheckinEntity.builder()
                .memberCardId(card.getId()).checkinType(com.mannschaft.app.membership.CheckinType.SELF)
                .checkedInAt(time.now()).location("既存受付").build());
        var oldCard = jdbc.queryForMap("SELECT scope_type,scope_id,status,checkin_count,total_spend,last_checkin_at FROM member_cards WHERE id=?",card.getId());
        var oldCheckinValues = jdbc.queryForMap("SELECT * FROM member_card_checkins WHERE id=?",oldCheckin.getId());
        autoSelfAward(a,1);
        assertThat(store.getAccounts().findByTeamIdAndUserId(b,actorId)).isEmpty();
        autoSelfAward(b,7);
        assertThat(store.getAccounts().findByTeamIdAndUserId(a,actorId).orElseThrow().getBalance()).isEqualTo(1);
        assertThat(store.getAccounts().findByTeamIdAndUserId(b,actorId).orElseThrow().getBalance()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id=?",Long.class,actorId)).isEqualTo(1);
        assertThat(memberships.findById(membership.getId()).orElseThrow().getLeftAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_cards WHERE user_id=?",Long.class,actorId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT scope_type,scope_id,status,checkin_count,total_spend,last_checkin_at FROM member_cards WHERE id=?",card.getId())).isEqualTo(oldCard);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_card_checkins WHERE member_card_id=?",Long.class,card.getId())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM member_card_checkins WHERE id=?",oldCheckin.getId())).isEqualTo(oldCheckinValues);
    }

    @Test
    void 補強_AC06委任解除した副管理者は保存済みreceiptも403になる() throws Exception {
        Long team = team();
        memberships.saveAndFlush(com.mannschaft.app.membership.entity.MembershipEntity.builder().userId(actorId)
                .scopeType(com.mannschaft.app.membership.domain.ScopeType.TEAM).scopeId(team)
                .roleKind(com.mannschaft.app.membership.domain.RoleKind.MEMBER).joinedAt(time.now()).invitedBy(actorId).build());
        var deputy = roles.findByName("DEPUTY_ADMIN").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder()
                .name("DEPUTY_ADMIN").displayName("副管理者").priority(100).isSystem(true).build()));
        userRoles.saveAndFlush(UserRoleEntity.builder().userId(actorId).roleId(deputy.getId()).teamId(team).grantedBy(actorId).build());
        var permission = permissions.findByNameIn(java.util.List.of("TEAM_LOYALTY_OPERATE")).stream().findFirst()
                .orElseGet(() -> permissions.saveAndFlush(com.mannschaft.app.role.entity.PermissionEntity.builder()
                        .name("TEAM_LOYALTY_OPERATE").displayName("ポイント操作").scope(com.mannschaft.app.role.entity.PermissionEntity.Scope.TEAM).build()));
        var group = permissionGroups.saveAndFlush(com.mannschaft.app.role.entity.PermissionGroupEntity.builder().teamId(team)
                .targetRole(com.mannschaft.app.role.entity.PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("ポイント委任").createdBy(actorId).build());
        groupPermissions.saveAndFlush(com.mannschaft.app.role.entity.PermissionGroupPermissionEntity.builder()
                .groupId(group.getId()).permissionId(permission.getId()).build());
        var assignment = userGroups.saveAndFlush(com.mannschaft.app.role.entity.UserPermissionGroupEntity.builder()
                .userId(actorId).groupId(group.getId()).assignedBy(actorId).build());
        UUID key = UUID.randomUUID(); var rule = store.getRules().saveAndFlush(LoyaltyTestFixture.rule(team));
        var body = new StaffAwardRequest("invalid-platform-qr",rule.getId(),0L,0L);
        var saved = receipt(team,actorId,key,RequestOperation.STAFF_AWARD,rule.getId(),new LoyaltyPayloadHasher().staffAward(team,body));
        String path = "/api/v1/teams/"+team+"/loyalty/awards";
        mvc.perform(post(path).with(user(actorId.toString())).header("Idempotency-Key",key)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(saved.getId().toString()));
        userGroups.delete(assignment); userGroups.flush();
        mvc.perform(post(path).with(user(actorId.toString())).header("Idempotency-Key",key)
                        .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("LOYALTY_002"));
    }

    private void autoSelfAward(Long team, int points) throws Exception {
        var program = LoyaltyTestFixture.program(team); program.update(true,Audience.ALL_AUTHENTICATED,SelfAwardMode.AUTO,time.now());
        store.getPrograms().saveAndFlush(program);
        var rule = LoyaltyTestFixture.rule(team); rule.update("来店",points,Frequency.ONCE_PER_JST_DAY,true,true,time.now());
        store.getRules().saveAndFlush(rule); var location = location(team,true);
        String token = qr.generateLocationQrToken(location.getLocationCode(),location.getLocationSecret());
        mvc.perform(post("/api/v1/teams/"+team+"/loyalty/self-awards").with(user(actorId.toString()))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json.writeValueAsString(new SelfAwardRequest(token,rule.getId(),1L,1L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.transaction.delta").value(points));
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
