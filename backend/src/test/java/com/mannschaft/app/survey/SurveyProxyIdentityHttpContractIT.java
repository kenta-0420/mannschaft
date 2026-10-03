package com.mannschaft.app.survey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.service.SurveyResponseService;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F14.1 本人への紐付け・機能と組合を限定した同意の HTTP 試練。
 * 実 JWT / SecurityFilterChain / Testcontainers MySQL を通し、actor と subject を必ず分離する。
 * HTTP 全体をテストトランザクションで包まず、永続化と拒否後の不変性を別トランザクションで読む。
 * RANDOM_PORT 構成は既存 SurveyDateTimeTimezoneHttpRoundTripIntegrationTest と同じく AFTER_CLASS で破棄する。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F14.1 アンケート代理回答の本人・同意範囲 HTTP契約")
class SurveyProxyIdentityHttpContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private TeamRepository teams;
    @Autowired private TeamOrgMembershipRepository affiliations;
    @Autowired private ProxyInputConsentRepository consents;
    @Autowired private ProxyInputRecordRepository records;
    @Autowired private SurveyRepository surveys;
    @Autowired private SurveyQuestionRepository questions;
    @Autowired private SurveyTargetRepository targets;
    @Autowired private SurveyResponseRepository responses;
    @Autowired private ProxyInputContext proxyContext;
    @Autowired private SurveyResponseService responseService;

    private Long actor;
    private Long subject;
    private Long approver;
    private OrganizationEntity org;
    private OrganizationEntity foreignOrg;
    private TeamEntity team;
    private SurveyEntity survey;
    private SurveyQuestionEntity question;
    private Long consentId;

    @BeforeEach
    void 本人と代理者が異なる同意と公開アンケートを保存する() {
        inTx(() -> {
            actor = user("代理者");
            subject = user("本人");
            approver = user("承認者");
            org = organization();
            foreignOrg = organization();
            team = teams.save(TeamEntity.builder().slug("proxy-identity-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("本人紐付け試練").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
            for (Long id : List.of(actor, subject, approver)) {
                MembershipTestHelper.insertMembership(em, id, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER);
                MembershipTestHelper.insertMembership(em, id, ScopeType.ORGANIZATION, foreignOrg.getId(), RoleKind.MEMBER);
                MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
            }
            MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, org.getId());
            MembershipTestHelper.insertUserRole(em, actor, "ADMIN", null, foreignOrg.getId());
            MembershipTestHelper.insertUserRole(em, approver, "ADMIN", null, org.getId());
            survey = surveys.save(SurveyEntity.builder().scopeType("ORGANIZATION").scopeId(org.getId())
                    .title("代理回答の本人紐付け試練").status(SurveyStatus.PUBLISHED).createdBy(approver).build());
            question = questions.save(SurveyQuestionEntity.builder().surveyId(survey.getId())
                    .questionType(QuestionType.FREE_TEXT).questionText("回答").build());
            ProxyInputConsentEntity consent = consents.save(ProxyInputConsentEntity.builder()
                    .subjectUserId(subject).proxyUserId(actor).organizationId(org.getId())
                    .consentMethod(ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED)
                    .effectiveFrom(LocalDate.now().minusDays(1)).effectiveUntil(LocalDate.now().plusDays(30))
                    .approvedByUserId(approver).approvedAt(LocalDateTime.now()).build());
            consentId = consent.getId();
            // 同意登録機能の別試練とは分離し、正本の FK を持つ機能行を fixture として直接作る。
            em.createNativeQuery("INSERT INTO proxy_input_consent_scopes "
                            + "(proxy_input_consent_id, feature_scope, created_at) VALUES (:cid, 'SURVEY', NOW())")
                    .setParameter("cid", consentId).executeUpdate();
            return null;
        });
        assertThat(actor).isNotEqualTo(subject);
    }

    @AfterEach
    void 所有したfixtureだけを片付ける() {
        // setup の全保存は単一 transaction。失敗時は rollback 済みで所有 survey は存在しない。
        if (survey == null) return;
        inTx(() -> {
            for (String table : List.of("survey_responses", "survey_targets", "survey_questions")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE survey_id = :sid")
                        .setParameter("sid", survey.getId()).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM surveys WHERE id = :sid").setParameter("sid", survey.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM proxy_input_records WHERE proxy_input_consent_id = :cid")
                    .setParameter("cid", consentId).executeUpdate();
            em.createNativeQuery("DELETE FROM proxy_input_consent_scopes WHERE proxy_input_consent_id = :cid")
                    .setParameter("cid", consentId).executeUpdate();
            em.createNativeQuery("DELETE FROM proxy_input_consents WHERE id = :cid").setParameter("cid", consentId).executeUpdate();
            em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :tid").setParameter("tid", team.getId()).executeUpdate();
            for (Long id : List.of(actor, subject, approver)) {
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid").setParameter("uid", id).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid").setParameter("uid", id).executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id = :uid").setParameter("uid", id).executeUpdate();
            }
            teams.deleteById(team.getId());
            organizations.deleteById(org.getId());
            organizations.deleteById(foreignOrg.getId());
            return null;
        });
    }

    @Test
    void 代理回答は本人へ保存し記録の代理者と本人を分離する() throws Exception {
        post(true).andExpect(status().isCreated()).andExpect(jsonPath("$.data[0].userId").value(subject));
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), actor))).isEmpty();
        SurveyResponseEntity saved = inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject).getFirst());
        assertThat(saved.getIsProxyInput()).isTrue();
        var record = inTx(() -> records.findById(saved.getProxyInputRecordId()).orElseThrow());
        assertThat(record.getProxyUserId()).isEqualTo(actor);
        assertThat(record.getSubjectUserId()).isEqualTo(subject);
        assertThat(record.getProxyInputConsentId()).isEqualTo(consentId);
        assertThat(inTx(() -> surveys.findById(survey.getId()).orElseThrow().getResponseCount())).isEqualTo(1);
    }

    @Test
    void 代理の回答取得は本人を返し通常の回答取得は代理者を返す() throws Exception {
        seedAnswer(actor, "代理者自身の回答");
        seedAnswer(subject, "本人の回答");
        get(true).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(subject))
                .andExpect(jsonPath("$.data[0].textResponse").value("本人の回答"));
        get(false).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(actor))
                .andExpect(jsonPath("$.data[0].textResponse").value("代理者自身の回答"));
    }

    @Test
    void 代理者自身の既回答は本人の初回回答を妨げず変更しない() throws Exception {
        seedAnswer(actor, "代理者自身の回答");
        post(true).andExpect(status().isCreated());
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), actor)))
                .singleElement().extracting(SurveyResponseEntity::getTextResponse).isEqualTo("代理者自身の回答");
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject))).hasSize(1);
    }

    @Test
    void 本人の既回答は重複409で保存も記録も増やさない() throws Exception {
        seedAnswer(subject, "本人の既回答");
        var before = snapshot();
        post(true).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SURVEY_006"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"true,201", "false,403"})
    void 限定配信は代理者でなく本人の対象指定で判定する(boolean subjectTarget, int expected) throws Exception {
        inTx(() -> {
            surveys.save(survey.toBuilder().distributionMode(DistributionMode.TARGETED).build());
            targets.save(SurveyTargetEntity.builder().surveyId(survey.getId()).userId(subjectTarget ? subject : actor).build());
            return null;
        });
        var before = snapshot();
        post(true).andExpect(status().is(expected));
        if (expected == 403) assertThat(snapshot()).isEqualTo(before);
        else assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject))).hasSize(1);
    }

    @Test
    void 全員配信も本人が配信母集団外なら拒否する() throws Exception {
        inTx(() -> { em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid AND scope_type = 'ORGANIZATION' AND scope_id = :oid")
                .setParameter("uid", subject).setParameter("oid", org.getId()).executeUpdate(); return null; });
        var before = snapshot();
        post(true).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 全員配信の本人サポーターは包含設定に従う(boolean includeSupporters) throws Exception {
        inTx(() -> {
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid AND scope_type = 'ORGANIZATION' AND scope_id = :oid")
                    .setParameter("uid", subject).setParameter("oid", org.getId()).executeUpdate();
            MembershipTestHelper.insertMembership(em, subject, ScopeType.ORGANIZATION, org.getId(), RoleKind.SUPPORTER);
            surveys.save(survey.toBuilder().includeSupporters(includeSupporters).build());
            return null;
        });
        var before = snapshot();
        post(true).andExpect(status().is(includeSupporters ? 201 : 403));
        if (!includeSupporters) assertThat(snapshot()).isEqualTo(before);
        else assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject))).hasSize(1);
    }

    @Test
    void 本人の再回答だけを置換し代理者の既回答を維持する() throws Exception {
        seedAnswer(actor, "代理者自身の回答");
        seedAnswer(subject, "本人の旧回答");
        inTx(() -> { surveys.save(survey.toBuilder().allowMultipleSubmissions(true).build()); return null; });
        post(true).andExpect(status().isCreated());
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), actor)))
                .singleElement().extracting(SurveyResponseEntity::getTextResponse).isEqualTo("代理者自身の回答");
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject)))
                .singleElement().extracting(SurveyResponseEntity::getTextResponse).isEqualTo("本人の回答");
    }

    @ParameterizedTest
    @CsvSource({"scope,POST", "scope,GET", "payment,POST", "payment,GET", "foreign,POST", "foreign,GET",
            "permission,POST", "permission,GET", "revoked,POST", "revoked,GET", "expired,POST", "expired,GET",
            "pending,POST", "pending,GET", "actor,POST", "actor,GET", "subject,POST", "subject,GET"})
    void 不適合な同意では代理の保存も閲覧も拒否する(String invalid, String method) throws Exception {
        inTx(() -> {
            switch (invalid) {
                case "scope", "payment" -> em.createNativeQuery("UPDATE proxy_input_consent_scopes SET feature_scope = :scope WHERE proxy_input_consent_id = :cid")
                        .setParameter("scope", "payment".equals(invalid) ? "PAYMENT" : "SCHEDULE_ATTENDANCE").setParameter("cid", consentId).executeUpdate();
                case "foreign" -> em.createNativeQuery("UPDATE proxy_input_consents SET organization_id = :oid WHERE id = :cid")
                        .setParameter("oid", foreignOrg.getId()).setParameter("cid", consentId).executeUpdate();
                case "permission" -> em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid")
                        .setParameter("uid", actor).executeUpdate();
                case "revoked" -> consents.findById(consentId).orElseThrow().revoke(ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT, null, "試練");
                case "expired" -> em.createNativeQuery("UPDATE proxy_input_consents SET effective_until = :date WHERE id = :cid")
                        .setParameter("date", LocalDate.now().minusDays(1)).setParameter("cid", consentId).executeUpdate();
                case "pending" -> em.createNativeQuery("UPDATE proxy_input_consents SET approved_at = NULL WHERE id = :cid")
                        .setParameter("cid", consentId).executeUpdate();
                case "actor", "subject" -> { /* DB検証に反するヘッダー又はJWTを後で指定する。 */ }
                default -> throw new IllegalArgumentException(invalid);
            }
            return null;
        });
        seedAnswer(subject, "拒否すべき本人の回答");
        var before = snapshot();
        var req = http(HttpMethod.valueOf(method), "GET".equals(method) ? "/me" : "", "actor".equals(invalid) ? approver : actor);
        proxyHeaders(req, "subject".equals(invalid) ? actor : subject);
        if ("POST".equals(method)) req.contentType(MediaType.APPLICATION_JSON).content(body());
        mvc.perform(req).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"startToday,true", "endToday,true", "endedYesterday,false", "startsTomorrow,false"})
    void 有効同意の期間境界はDB日付でなく業務日付に従う(String boundary, boolean active) {
        inTx(() -> {
            LocalDate today = LocalDate.now();
            LocalDate from = "startToday".equals(boundary) ? today
                    : "startsTomorrow".equals(boundary) ? today.plusDays(1) : today.minusDays(3);
            LocalDate until = "endToday".equals(boundary) ? today
                    : "endedYesterday".equals(boundary) ? today.minusDays(1) : today.plusDays(3);
            em.createNativeQuery("UPDATE proxy_input_consents SET effective_from = :fromDate, effective_until = :untilDate WHERE id = :cid")
                    .setParameter("fromDate", from).setParameter("untilDate", until)
                    .setParameter("cid", consentId).executeUpdate();
            String originalZone = em.createNativeQuery("SELECT @@session.time_zone").getSingleResult().toString();
            try {
                em.createNativeQuery("SET SESSION time_zone = :zone").setParameter("zone", "-12:00").executeUpdate();
                LocalDate databaseDate = LocalDate.parse(em.createNativeQuery("SELECT CURRENT_DATE").getSingleResult().toString());
                String sessionZone = "-12:00";
                if (databaseDate.equals(today)) {
                    sessionZone = "+14:00";
                    em.createNativeQuery("SET SESSION time_zone = :zone").setParameter("zone", sessionZone).executeUpdate();
                    databaseDate = LocalDate.parse(em.createNativeQuery("SELECT CURRENT_DATE").getSingleResult().toString());
                }
                assertThat(databaseDate).isNotEqualTo(today);
                System.out.printf("DATE_BOUNDARY_PROOF {\"businessDate\":\"%s\",\"databaseDate\":\"%s\",\"sessionZone\":\"%s\",\"jvmZone\":\"%s\",\"boundary\":\"%s\"}%n",
                        today, databaseDate, sessionZone, java.time.ZoneId.systemDefault(), boundary);
                assertThat(consents.findValidConsent(consentId, actor).isPresent()).isEqualTo(active);
                assertThat(consents.findActiveByProxyUserId(actor).stream()
                        .anyMatch(consent -> consent.getId().equals(consentId))).isEqualTo(active);
            } finally {
                em.createNativeQuery("SET SESSION time_zone = :zone").setParameter("zone", originalZone).executeUpdate();
            }
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void 代理回答のアンケートID形式不正は400で永続化を変えない(String method) throws Exception {
        var before = snapshot();
        String suffix = "GET".equals(method) ? "/me" : "";
        var req = request(HttpMethod.valueOf(method), "/api/v1/surveys/not-a-number/responses" + suffix)
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")));
        proxyHeaders(req, subject);
        if ("POST".equals(method)) req.contentType(MediaType.APPLICATION_JSON).content(body());
        mvc.perform(req).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void 未所属の非公開アンケートと不在IDは同じ404で存在を秘匿する(String method) throws Exception {
        inTx(() -> {
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :uid AND organization_id = :oid")
                    .setParameter("uid", actor).setParameter("oid", foreignOrg.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :uid AND scope_type = 'ORGANIZATION' AND scope_id = :oid")
                    .setParameter("uid", actor).setParameter("oid", foreignOrg.getId()).executeUpdate();
            em.createNativeQuery("UPDATE surveys SET scope_id = :oid WHERE id = :sid")
                    .setParameter("oid", foreignOrg.getId()).setParameter("sid", survey.getId()).executeUpdate();
            return null;
        });
        seedAnswer(subject, "閲覧できない本人の回答");
        var before = snapshot();
        var observed = new java.util.ArrayList<org.springframework.mock.web.MockHttpServletResponse>();
        for (Long id : List.of(survey.getId(), 999_999_999L)) {
            String suffix = "GET".equals(method) ? "/me" : "";
            var req = request(HttpMethod.valueOf(method), "/api/v1/surveys/" + id + "/responses" + suffix)
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")))
                    .header("Accept-Language", "ja");
            proxyHeaders(req, subject);
            if ("POST".equals(method)) req.contentType(MediaType.APPLICATION_JSON).content(body());
            observed.add(mvc.perform(req).andReturn().getResponse());
        }
        var foreignError = mapper.readTree(observed.get(0).getContentAsString()).path("error");
        var missingError = mapper.readTree(observed.get(1).getContentAsString()).path("error");
        System.out.println("PRIVATE_ID_PROOF " + mapper.writeValueAsString(Map.of(
                "method", method, "foreignStatus", observed.get(0).getStatus(), "foreignError", foreignError,
                "missingStatus", observed.get(1).getStatus(), "missingError", missingError)));
        assertThat(snapshot()).isEqualTo(before);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(observed.get(0).getStatus()).isEqualTo(404),
                () -> assertThat(observed.get(1).getStatus()).isEqualTo(404),
                () -> assertThat(foreignError.path("code").asText()).isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND.getCode()),
                () -> assertThat(missingError.path("code").asText()).isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND.getCode()),
                () -> assertThat(foreignError.path("message").asText()).isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND.getMessage()),
                () -> assertThat(missingError.path("message").asText()).isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND.getMessage()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void チームは同意組合へ有効加盟している場合だけ代理回答できる(boolean active) throws Exception {
        inTx(() -> {
            surveys.save(survey.toBuilder().scopeType("TEAM").scopeId(team.getId()).build());
            affiliations.save(TeamOrgMembershipEntity.builder().teamId(team.getId()).organizationId(org.getId())
                    .status(active ? TeamOrgMembershipEntity.Status.ACTIVE : TeamOrgMembershipEntity.Status.PENDING)
                    .invitedAt(LocalDateTime.now()).build());
            return null;
        });
        var before = snapshot();
        post(true).andExpect(status().is(active ? 201 : 403));
        if (!active) assertThat(snapshot()).isEqualTo(before);
        else assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject))).hasSize(1);
    }

    @Test
    void 終了後も有効同意で本人の既回答を参照できる() throws Exception {
        seedAnswer(subject, "終了後も本人の回答");
        inTx(() -> { surveys.findById(survey.getId()).orElseThrow().close(); return null; });
        get(true).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].textResponse").value("終了後も本人の回答"));
    }

    @Test
    void 本人未回答なら代理の回答取得は空配列を返す() throws Exception {
        seedAnswer(actor, "代理者だけ既回答");
        get(true).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 通常回答は認証本人へ保存し代理記録を作らない() throws Exception {
        post(false).andExpect(status().isCreated()).andExpect(jsonPath("$.data[0].userId").value(actor));
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), actor))).hasSize(1);
        assertThat(inTx(() -> responses.findBySurveyIdAndUserId(survey.getId(), subject))).isEmpty();
        assertThat(inTx(() -> records.findByProxyInputConsentIdOrderByCreatedAtDesc(consentId))).isEmpty();
    }

    @Test
    void 途中の不正設問は全回答と記録と件数をロールバックする() throws Exception {
        var before = snapshot();
        var req = http(HttpMethod.POST, "", actor);
        proxyHeaders(req, subject);
        req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("answers", List.of(
                Map.of("questionId", question.getId(), "textResponse", "先行回答"),
                Map.of("questionId", 999_999_999L, "textResponse", "不正回答")))));
        mvc.perform(req).andExpect(status().isNotFound());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void 変身で同意検証時の代理者が変わる操作は拒否する(String method) throws Exception {
        inTx(() -> { MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null); return null; });
        seedAnswer(subject, "本人の既回答");
        var before = snapshot();
        var req = request(HttpMethod.valueOf(method), "/api/v1/surveys/" + survey.getId() + "/responses"
                + ("GET".equals(method) ? "/me" : ""))
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("SYSTEM_ADMIN")))
                .header("X-Admin-Impersonate-User-Id", subject);
        proxyHeaders(req, subject);
        if ("POST".equals(method)) req.contentType(MediaType.APPLICATION_JSON).content(body());
        mvc.perform(req).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 事前認可を通していないContextのService直呼出しは拒否する(boolean submit) {
        var previousAttributes = RequestContextHolder.getRequestAttributes();
        var previousAuthentication = SecurityContextHolder.getContext().getAuthentication();
        var attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        var before = snapshot();
        RequestContextHolder.setRequestAttributes(attributes);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        try {
            // request state を直接作っても、実 HTTP の同意・権限・scope 検証を代用できない。
            proxyContext.activate(subject, consentId, "PAPER_FORM", "試練保管場所", Set.of(FeatureScope.SURVEY));
            assertThatThrownBy(() -> {
                if (submit) responseService.submitResponse(survey.getId(), actor, new SubmitResponseRequest(List.of(
                        new SubmitResponseRequest.AnswerEntry(question.getId(), null, "本人の回答"))));
                else responseService.getMyResponses(survey.getId(), actor);
            }).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_002);
        } finally {
            proxyContext.clear();
            attributes.requestCompleted();
            RequestContextHolder.setRequestAttributes(previousAttributes);
            SecurityContextHolder.getContext().setAuthentication(previousAuthentication);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.ResultActions post(boolean proxy) throws Exception {
        var req = http(HttpMethod.POST, "", actor).contentType(MediaType.APPLICATION_JSON).content(body());
        if (proxy) proxyHeaders(req, subject);
        return mvc.perform(req);
    }
    private org.springframework.test.web.servlet.ResultActions get(boolean proxy) throws Exception {
        var req = http(HttpMethod.GET, "/me", actor);
        if (proxy) proxyHeaders(req, subject);
        return mvc.perform(req);
    }
    private MockHttpServletRequestBuilder http(HttpMethod method, String suffix, Long id) {
        return request(method, "/api/v1/surveys/" + survey.getId() + "/responses" + suffix)
                .header("Authorization", "Bearer " + tokens.issueAccessToken(id, List.of("USER")));
    }
    private void proxyHeaders(MockHttpServletRequestBuilder req, Long id) {
        req.header("X-Proxy-For-User-Id", id).header("X-Proxy-Consent-Id", consentId)
                .header("X-Proxy-Input-Source", "PAPER_FORM").header("X-Proxy-Original-Storage", "試練専用保管場所");
    }
    private String body() throws Exception {
        return mapper.writeValueAsString(Map.of("answers", List.of(Map.of("questionId", question.getId(), "textResponse", "本人の回答"))));
    }
    private void seedAnswer(Long id, String text) {
        inTx(() -> responses.save(SurveyResponseEntity.builder().surveyId(survey.getId()).questionId(question.getId())
                .userId(id).textResponse(text).build()));
    }
    private List<Object> snapshot() {
        return inTx(() -> List.of(responses.findBySurveyIdOrderByCreatedAtAsc(survey.getId()).stream()
                        .map(r -> List.of(r.getId(), r.getUserId(), r.getTextResponse())).toList(),
                records.findByProxyInputConsentIdOrderByCreatedAtDesc(consentId).size(),
                surveys.findById(survey.getId()).orElseThrow().getResponseCount()));
    }
    private <T> T inTx(Supplier<T> work) { return new TransactionTemplate(txManager).execute(status -> work.get()); }
    private Long user(String name) {
        return users.save(UserEntity.builder().email("proxy-identity-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(name).displayName(name).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
    private OrganizationEntity organization() {
        return organizations.save(OrganizationEntity.builder().slug("proxy-identity-" + UUID.randomUUID().toString().substring(0, 8))
                .name("本人紐付け試練組合").orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC).hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false).build());
    }
}
