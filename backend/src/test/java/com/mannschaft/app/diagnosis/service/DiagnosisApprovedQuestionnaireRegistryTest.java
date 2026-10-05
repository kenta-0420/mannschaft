package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 合成バイトのみで登録機構を検証する。本物原稿の承認、実resource登録、公開gate合格の証拠ではない。 */
class DiagnosisApprovedQuestionnaireRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, String> TEXT = Map.of("ja", "合成試験のみ", "en", "synthetic test only",
            "zh", "合成試験のみ", "ko", "synthetic test only", "es", "synthetic test only", "de", "synthetic test only");

    private DiagnosisQuestionnaireCatalog.Definition definition(String version) {
        var questions = new ArrayList<DiagnosisQuestion>();
        var ties = new ArrayList<DiagnosisTieQuestion>();
        var axes = new EnumMap<DiagnosisAxis, Map<String, String>>(DiagnosisAxis.class);
        for (DiagnosisAxis axis : DiagnosisAxis.values()) {
            for (int i = 0; i < 4; i++) questions.add(new DiagnosisQuestion(axis.name() + i, axis, i % 2 == 0 ? 1 : -1, TEXT));
            ties.add(new DiagnosisTieQuestion(axis, TEXT, TEXT)); axes.put(axis, TEXT);
        }
        return new DiagnosisQuestionnaireCatalog.Definition(DiagnosisQuestionnaireCatalog.SNAPSHOT_SCHEMA_VERSION,
                DiagnosisQuestionnaireCatalog.SnapshotApproval.APPROVED, version, "signed-centered-v1",
                questions, ties, TEXT, axes);
    }
    private ObjectNode pack(DiagnosisQuestionnaireCatalog.Definition... definitions) {
        var root = mapper.createObjectNode().put("approved", true).put("translationsApproved", true);
        root.set("catalogs", mapper.valueToTree(List.of(definitions))); return root;
    }
    private DiagnosisApprovedQuestionnaireRegistry registry(ObjectNode pack, String version) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(pack);
        return new DiagnosisApprovedQuestionnaireRegistry(DiagnosisApprovedQuestionnaireRegistry.verify(bytes,
                version, sha(bytes), mapper));
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test void missingRegistrationNeverApprovesInAnyProfile() {
        for (String profile : List.of("prod", "production", "dev", "test", "ranch-isolated")) {
            var env = new MockEnvironment(); env.setActiveProfiles(profile);
            env.setProperty("ranch.diagnosis.draft-enabled", "true");
            var registry = new DiagnosisApprovedQuestionnaireRegistry(env, mapper);
            assertThat(registry.current()).isEmpty();
            assertThat(new DiagnosisPublicationReadiness(registry).approvedCatalogAvailable()).isFalse();
        }
    }

    @Test void explicitIncompleteOrDraftResourceRegistrationRefusesStartup() {
        for (String field : List.of("resource", "version", "sha256")) {
            var env = new MockEnvironment().withProperty("mannschaft.diagnosis.approved-catalog." + field, "invalid");
            assertThatThrownBy(() -> new DiagnosisApprovedQuestionnaireRegistry(env, mapper))
                    .isInstanceOf(IllegalStateException.class);
        }
        var env = new MockEnvironment().withProperty("mannschaft.diagnosis.approved-catalog.resource",
                "diagnosis/questionnaire-draft-20261003.json");
        assertThatThrownBy(() -> new DiagnosisApprovedQuestionnaireRegistry(env, mapper)).isInstanceOf(IllegalStateException.class);
    }

    @Test void byteDigestApprovalTranslationVersionAndScoringMustMatch() throws Exception {
        var root = pack(definition("synthetic-v1")); byte[] bytes = mapper.writeValueAsBytes(root);
        assertThatThrownBy(() -> DiagnosisApprovedQuestionnaireRegistry.verify(bytes, "synthetic-v1", "0".repeat(64), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(root, "unknown-version")).isInstanceOf(IllegalArgumentException.class);
        for (String flag : List.of("approved", "translationsApproved")) {
            var invalid = root.deepCopy().put(flag, false);
            assertThatThrownBy(() -> registry(invalid, "synthetic-v1")).isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : List.of("scoringVersion", "snapshotSchemaVersion", "approval")) {
            var invalid = root.deepCopy(); ((ObjectNode) invalid.path("catalogs").get(0)).put(field, "unknown");
            assertThatThrownBy(() -> registry(invalid, "synthetic-v1")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void malformedQuestionAxisLocaleTieAndDuplicateVersionCannotRegister() {
        for (int corruption = 0; corruption < 9; corruption++) {
            var root = pack(definition("synthetic-v1")); var catalog = (ObjectNode) root.path("catalogs").get(0);
            var question = (ObjectNode) catalog.path("questions").get(0);
            switch (corruption) {
                case 0 -> question.put("id", catalog.path("questions").get(1).path("id").textValue());
                case 1 -> question.put("polarity", 0);
                case 2 -> ((ObjectNode) question.path("text")).remove("ko");
                case 3 -> ((ObjectNode) catalog.path("ties").get(0)).put("axisId", catalog.path("ties").get(1).path("axisId").textValue());
                case 4 -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("catalogs")).add(catalog.deepCopy());
                case 5 -> ((com.fasterxml.jackson.databind.node.ArrayNode) catalog.path("questions")).remove(0);
                case 6 -> question.put("axis", catalog.path("questions").get(4).path("axis").textValue());
                case 7 -> question.put("polarity", "1");
                case 8 -> ((ObjectNode) question.path("text")).put("ja", 123);
            }
            assertThatThrownBy(() -> registry(root, "synthetic-v1")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void registeredStartReadinessAndHistoricalSnapshotUseSameTrustedDefinitions() throws Exception {
        var old = definition("synthetic-v1"); var current = definition("synthetic-v2");
        var registry = registry(pack(old, current), "synthetic-v2");
        var env = new MockEnvironment(); env.setActiveProfiles("production");
        var catalog = new DiagnosisQuestionnaireCatalog(mapper, env, registry);
        assertThat(new DiagnosisPublicationReadiness(registry).approvedCatalogAvailable()).isTrue();
        var readiness = new DiagnosisPublicationReadiness(registry);
        assertThat(readiness.approvedVersionAvailable("synthetic-v1", "signed-centered-v1")).isTrue();
        assertThat(readiness.approvedVersionAvailable("synthetic-v2", "signed-centered-v1")).isTrue();
        assertThat(readiness.approvedVersionAvailable("draft-20261003-v1", "signed-centered-v1")).isFalse();
        assertThat(readiness.approvedVersionAvailable("synthetic-v2", "unknown-scoring")).isFalse();
        assertThat(catalog.forStart()).isEqualTo(current);
        var codec = new DiagnosisSessionSnapshotCodec(mapper);
        var frozen = codec.definition(codec.encodeDefinition(old));
        catalog.requireSnapshotReadable(frozen); catalog.requireMutationAllowed(frozen);
        assertThat(frozen).isEqualTo(old);
        var changed = new DiagnosisQuestionnaireCatalog.Definition(old.snapshotSchemaVersion(), old.approval(), old.questionnaireVersion(),
                old.scoringVersion(), old.questions(), old.ties(), Map.of("ja", "改竄"), old.axisDescriptions());
        assertThatThrownBy(() -> catalog.requireMutationAllowed(changed)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> catalog.requireSnapshotReadable(definition("unregistered-v3"))).isInstanceOf(BusinessException.class);
    }
}
