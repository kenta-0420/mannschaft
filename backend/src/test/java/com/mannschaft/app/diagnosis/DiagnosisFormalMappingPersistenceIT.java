package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.ConfirmedBirthNumbers;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.BirthStyleCalculator.BirthNumbers;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.entity.DiagnosisResultEntity;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.service.DiagnosisBirthResultWriter;
import com.mannschaft.app.diagnosis.service.DiagnosisPurgeService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 正式実resource・実Security・実Bean・MySQL commitで新snapshot/旧JSON/本人境界を観測する。 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "mannschaft.ranch.production-master.resource=ranch/approved/ranch-mapping-20261108-v1.json",
        "mannschaft.ranch.production-master.version=ranch-mapping-20261108-v1",
        "mannschaft.ranch.production-master.sha256=3037f7585d5bdb395bae86ac26276ac70e61b5c680629bacf02a2978f50eab1a",
        "mannschaft.ranch.production-master.asset-pack-version=ranch-approved-20261108-v1",
        "mannschaft.ranch.production-master.asset-pack-sha256=62d2d045e480bab986619410168bfef145989937a2b994c8cc4e3eec9346d26b",
        "mannschaft.diagnosis.approved-catalog.resource=diagnosis/approved/diagnosis-20261108-v1.json",
        "mannschaft.diagnosis.approved-catalog.version=diagnosis-20261108-v1",
        "mannschaft.diagnosis.approved-catalog.sha256=10e2d71bfd2d84791c3fdb3b82c42cce9dc55ed98feb55a095e6016a5198f9d0",
        "mannschaft.ranch.approved-visuals-available=true"})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DiagnosisFormalMappingPersistenceIT extends AbstractMySqlIntegrationTest {
    private static final String BASE = "/api/v1/me/diagnoses";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private DiagnosisResultRepository results;
    @Autowired private DiagnosisBirthResultWriter birthWriter;
    @Autowired private DiagnosisPurgeService purge;
    private Long owner;
    private Long other;

    @BeforeEach
    void 本人二人だけを作成する() {
        owner = createUser();
        other = createUser();
    }

    @AfterEach
    void この試験が作った本人結果だけを撤去する() {
        for (Long id : new Long[] {owner, other}) {
            if (id == null) continue;
            purge.purgeUser(id);
            users.deleteById(id);
        }
    }

    @Test
    void 正式診断は同点解消後にidentity説明をcommitし別本人と未認証を拒否する() throws Exception {
        var session = mutate(post(BASE + "/sessions"), Map.of(), 201);
        assertThat(session.path("questionnaireVersion").asText()).isEqualTo("diagnosis-20261108-v1");
        String sessionId = session.path("id").asText();
        var answers = new ArrayList<Map<String,Object>>();
        session.path("questions").forEach(q -> answers.add(Map.of("questionId", q.path("id").asText(), "value", 3)));
        session = mutate(put(BASE + "/sessions/" + sessionId + "/answers"),
                Map.of("version", session.path("version").asText(), "answers", answers), 200);
        session = mutate(post(BASE + "/sessions/" + sessionId + "/complete"), complete(session, List.of()), 200);
        assertThat(session.path("status").asText()).isEqualTo("TIE_BREAK_REQUIRED");
        assertThat(session.path("resultId").asText(null)).isNull();
        var ties = new ArrayList<Map<String,Object>>();
        for (var axis : DiagnosisAxis.values()) ties.add(Map.of("axisId", axis.name(), "value", 0));
        session = mutate(post(BASE + "/sessions/" + sessionId + "/complete"), complete(session, ties), 200);
        UUID id = UUID.fromString(session.path("resultId").asText());
        String frozen = results.findByIdAndUserId(id, owner).orElseThrow().getSummarySnapshot();
        var saved = read(id);
        assertThat(saved.path("speciesKey").asText()).isEqualTo("stegosaurus");
        assertThat(saved.path("variantKey").asText()).isEqualTo("V1");
        assertFrozen(saved);
        assertThat(read(id)).isEqualTo(saved);
        assertThat(results.findByIdAndUserId(id, owner).orElseThrow().getSummarySnapshot()).isEqualTo(frozen);
        mvc.perform(get(BASE + "/results/" + id).with(user(other.toString()))).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/results/" + id)).andExpect(status().isUnauthorized());
        // care rule/未参加状態は正式素材の独立公開boolや本人結果閲覧の可否を決めない。
        mvc.perform(get("/api/v1/me/ranch").with(user(owner.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approvedVisualsAvailable").value(true));
    }

    @Test
    void 出生snapshotは内部派生数だけを固定し再送と旧null結果再読込で書換えない() throws Exception {
        UUID key = UUID.randomUUID();
        var confirmed = new ConfirmedBirthNumbers(new BirthNumbers(1, 9, 10, 18,
                "nfkc-hepburn-v1", "digit-reduce-v1"), 1);
        var birth = birthWriter.create(owner, key, "a".repeat(64), confirmed);
        String frozen = results.findByIdAndUserId(birth.id(), owner).orElseThrow().getSummarySnapshot();
        assertFrozen(read(birth.id()));
        assertThat(birthWriter.create(owner, key, "a".repeat(64), confirmed)).isEqualTo(birth);
        assertThat(frozen).doesNotContain("birthDate", "lastNameKana", "firstNameKana", "answers");
        var legacy = new DiagnosisResultSummary(UuidV7.generate(), DiagnosisMethod.BIRTH_STYLE, Instant.EPOCH,
                "diagnosis-result-v1", null, null, "nfkc-hepburn-v1", "digit-reduce-v1", null,
                null, Map.of(), birth.numberSummary(), Map.of("ja", "旧結果の説明"), Map.of());
        var old = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(legacy);
        old.remove(List.of("speciesKey", "variantKey", "catalogVersion", "mappingDescriptionSnapshot"));
        String original = json.writeValueAsString(old);
        results.saveAndFlush(DiagnosisResultEntity.builder().id(legacy.id()).userId(owner).method(legacy.method())
                .summarySnapshot(original).completedAt(Instant.EPOCH).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build());
        assertThat(read(legacy.id()).path("mappingVersion").asText(null)).isNull();
        assertThat(read(legacy.id()).path("speciesKey").asText(null)).isNull();
        assertThat(results.findByIdAndUserId(legacy.id(), owner).orElseThrow().getSummarySnapshot()).isEqualTo(original);
        assertThat(results.findByIdAndUserId(birth.id(), owner).orElseThrow().getSummarySnapshot()).isEqualTo(frozen);
    }

    private Map<String,Object> complete(JsonNode session, List<Map<String,Object>> ties) {
        return Map.of("version", session.path("version").asText(), "answerRevision", session.path("answerRevision").asText(), "tieAnswers", ties);
    }

    private JsonNode mutate(MockHttpServletRequestBuilder request, Object body, int code) throws Exception {
        return json.readTree(mvc.perform(request.with(user(owner.toString())).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().is(code))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode read(UUID id) throws Exception {
        return json.readTree(mvc.perform(get(BASE + "/results/" + id).with(user(owner.toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private void assertFrozen(JsonNode saved) {
        assertThat(saved.path("mappingVersion").asText()).isEqualTo("ranch-mapping-20261108-v1");
        assertThat(saved.path("catalogVersion").asText()).isEqualTo("20261108");
        assertThat(saved.path("mappingDescriptionSnapshot").size()).isEqualTo(6);
        assertThat(saved.path("mappingDescriptionSnapshot").path("ja").asText()).contains("外見");
    }

    private Long createUser() {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@formal-mapping.invalid")
                .lastName("試験").firstName("本人").displayName("本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
}
