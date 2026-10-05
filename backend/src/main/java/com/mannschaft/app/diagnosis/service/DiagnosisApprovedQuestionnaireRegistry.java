package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 明示登録された実バイトと承認済み質問・翻訳を検証し、開始版と旧保存版を同じ正本で保持する。 */
@Component
public final class DiagnosisApprovedQuestionnaireRegistry {
    private static final String PREFIX = "mannschaft.diagnosis.approved-catalog.";
    private static final Set<String> LOCALES = Set.of("ja", "en", "zh", "ko", "es", "de");
    private final Registration registration;

    @Autowired
    public DiagnosisApprovedQuestionnaireRegistry(Environment environment, ObjectMapper mapper) {
        String resource = environment.getProperty(PREFIX + "resource");
        String version = environment.getProperty(PREFIX + "version");
        String hash = environment.getProperty(PREFIX + "sha256");
        if (resource == null && version == null && hash == null) {
            registration = new Registration(null, Map.of());
            return;
        }
        if (resource == null || !resource.matches("diagnosis/approved/[A-Za-z0-9._/-]+\\.json")
                || resource.contains("..") || !versionToken(version) || !digest(hash)) {
            throw new IllegalStateException("正式診断catalogの明示登録が不正です");
        }
        try (var stream = new ClassPathResource(resource).getInputStream()) {
            registration = verify(stream.readAllBytes(), version, hash, mapper);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("正式診断catalogを検証できません", error);
        }
    }

    /** 登録内容を直接渡すのは同packageの合成UTだけ。実resourceを追加する登録入口ではない。 */
    DiagnosisApprovedQuestionnaireRegistry(Registration registration) {
        this.registration = registration;
    }

    public Optional<DiagnosisQuestionnaireCatalog.Definition> current() {
        if (registration.currentVersion() == null) return Optional.empty();
        return Optional.ofNullable(registration.definitions().get(registration.currentVersion()));
    }

    /** 保存flagやversionだけで承認せず、登録済みの不変表示・採点定義との完全一致を要求する。 */
    public boolean contains(DiagnosisQuestionnaireCatalog.Definition saved) {
        return saved != null && saved.questionnaireVersion() != null
                && saved.equals(registration.definitions().get(saved.questionnaireVersion()));
    }

    /** 恐竜masterが要求する質問版・採点版が明示承認登録された組であることを確認する。 */
    public boolean supports(String questionnaireVersion, String scoringVersion) {
        if (questionnaireVersion == null || scoringVersion == null) return false;
        var definition = registration.definitions().get(questionnaireVersion);
        return definition != null && definition.scoringVersion().equals(scoringVersion);
    }

    static Registration verify(byte[] bytes, String version, String hash, ObjectMapper mapper) {
        if (bytes == null || !versionToken(version) || !digest(hash)) throw invalid();
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equalsIgnoreCase(hash)) throw invalid();
            var root = mapper.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("approved").isBoolean()
                    || !root.path("approved").booleanValue() || !root.path("translationsApproved").isBoolean()
                    || !root.path("translationsApproved").booleanValue() || !root.path("catalogs").isArray()
                    || root.path("catalogs").isEmpty()) throw invalid();
            var definitions = new HashMap<String, DiagnosisQuestionnaireCatalog.Definition>();
            for (var node : root.path("catalogs")) {
                validateShape(node);
                var definition = mapper.treeToValue(node, DiagnosisQuestionnaireCatalog.Definition.class);
                validate(definition);
                if (definitions.putIfAbsent(definition.questionnaireVersion(), definition) != null) throw invalid();
            }
            if (!definitions.containsKey(version)) throw invalid();
            return new Registration(version, definitions);
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("正式診断catalogの内容が不正です", error);
        }
    }

    /** Jacksonの文字列・数値・enum強制変換を承認schemaの許可として扱わない。 */
    private static void validateShape(JsonNode node) {
        if (!node.isObject() || !node.path("approval").isTextual()
                || !"APPROVED".equals(node.path("approval").textValue())) throw invalid();
        for (String field : Set.of("snapshotSchemaVersion", "questionnaireVersion", "scoringVersion")) {
            if (!node.path(field).isTextual()) throw invalid();
        }
        if (!node.path("questions").isArray() || !node.path("ties").isArray()
                || !node.path("axisDescriptions").isObject()) throw invalid();
        for (var question : node.path("questions")) {
            if (!question.isObject() || !question.path("id").isTextual() || !question.path("axis").isTextual()
                    || !question.path("polarity").isIntegralNumber()
                    || !question.path("polarity").canConvertToInt()) throw invalid();
            textShape(question.path("text"));
        }
        for (var tie : node.path("ties")) {
            if (!tie.isObject() || !tie.path("axisId").isTextual()) throw invalid();
            textShape(tie.path("zero")); textShape(tie.path("one"));
        }
        textShape(node.path("descriptionSnapshot"));
        node.path("axisDescriptions").forEach(DiagnosisApprovedQuestionnaireRegistry::textShape);
    }

    private static void textShape(JsonNode node) {
        if (!node.isObject() || node.size() != 6) throw invalid();
        for (String locale : LOCALES) {
            if (!node.path(locale).isTextual() || node.path(locale).textValue().isBlank()) throw invalid();
        }
    }

    private static void validate(DiagnosisQuestionnaireCatalog.Definition definition) {
        if (definition == null
                || !DiagnosisQuestionnaireCatalog.SNAPSHOT_SCHEMA_VERSION.equals(definition.snapshotSchemaVersion())
                || definition.approval() != DiagnosisQuestionnaireCatalog.SnapshotApproval.APPROVED
                || !versionToken(definition.questionnaireVersion())
                || definition.questionnaireVersion().equals("draft-20261003-v1")
                || !"signed-centered-v1".equals(definition.scoringVersion())
                || definition.questions().size() != 24 || definition.ties().size() != 6
                || !definition.axisDescriptions().keySet().equals(Set.of(DiagnosisAxis.values()))) throw invalid();
        texts(definition.descriptionSnapshot());
        definition.axisDescriptions().values().forEach(DiagnosisApprovedQuestionnaireRegistry::texts);
        var ids = new HashSet<String>();
        var counts = new EnumMap<DiagnosisAxis, Integer>(DiagnosisAxis.class);
        for (var question : definition.questions()) {
            if (question == null || question.id() == null || question.id().isBlank() || !ids.add(question.id())
                    || question.axis() == null || Math.abs(question.polarity()) != 1) throw invalid();
            texts(question.text());
            counts.merge(question.axis(), 1, Integer::sum);
        }
        if (counts.size() != 6 || counts.values().stream().anyMatch(count -> count != 4)) throw invalid();
        var tieAxes = new HashSet<DiagnosisAxis>();
        for (var tie : definition.ties()) {
            if (tie == null || tie.axisId() == null || !tieAxes.add(tie.axisId())) throw invalid();
            texts(tie.zero()); texts(tie.one());
        }
        if (!tieAxes.equals(Set.of(DiagnosisAxis.values()))) throw invalid();
    }

    private static void texts(Map<String, String> texts) {
        if (texts == null || !texts.keySet().equals(LOCALES)
                || texts.values().stream().anyMatch(value -> value == null || value.isBlank())) throw invalid();
    }

    private static boolean versionToken(String value) {
        // 保存sessionのquestionnaire_version/scoring_versionは既存DDLのVARCHAR(80)。
        return value != null && value.length() <= 80 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }
    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("正式診断catalogの承認・版・構成が不正です");
    }
    record Registration(String currentVersion, Map<String, DiagnosisQuestionnaireCatalog.Definition> definitions) {
        Registration { definitions = Map.copyOf(definitions); }
    }
}
