package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 正式登録版と開発専用draftを分け、開始時の全表示と採点版を不変snapshotに固定する。 */
@Service
public class DiagnosisQuestionnaireCatalog {
    private final ObjectMapper mapper;
    private final Environment environment;
    private final DiagnosisApprovedQuestionnaireRegistry approved;

    @Autowired
    public DiagnosisQuestionnaireCatalog(ObjectMapper mapper, Environment environment,
            DiagnosisApprovedQuestionnaireRegistry approved) {
        this.mapper = mapper;
        this.environment = environment;
        this.approved = approved;
    }

    /** 既存合成UT用。環境の明示登録を省略した承認fixtureは作らない。 */
    public DiagnosisQuestionnaireCatalog(ObjectMapper mapper, Environment environment) {
        this(mapper, environment, new DiagnosisApprovedQuestionnaireRegistry(environment, mapper));
    }

    /** 正式登録があればその版を開始し、未登録なら従来の開発専用境界を使用する。 */
    public Definition forStart() {
        return approved.current().orElseGet(this::draft);
    }
    public static final String SNAPSHOT_SCHEMA_VERSION = "diagnosis-session-snapshot-v1";
    public enum SnapshotApproval { DRAFT, APPROVED }
    private static final List<String> LOCALES = List.of("ja", "en", "zh", "ko", "es", "de");

    /** Entityを公開せず、設問・同点表示・説明の不変値だけを返す。 */
    public record Definition(String snapshotSchemaVersion, SnapshotApproval approval, String questionnaireVersion, String scoringVersion,
            List<DiagnosisQuestion> questions, List<DiagnosisTieQuestion> ties,
            Map<String,String> descriptionSnapshot, Map<DiagnosisAxis, Map<String,String>> axisDescriptions) {
        public Definition {
            questions = List.copyOf(questions);
            descriptionSnapshot = Map.copyOf(descriptionSnapshot);
            ties = ties.stream().map(tie -> new DiagnosisTieQuestion(tie.axisId(), Map.copyOf(tie.zero()), Map.copyOf(tie.one()))).toList();
            var frozen = new EnumMap<DiagnosisAxis,Map<String,String>>(DiagnosisAxis.class);
            axisDescriptions.forEach((axis, descriptions) -> frozen.put(axis, Map.copyOf(descriptions)));
            axisDescriptions = Map.copyOf(frozen);
        }
    }

    public Definition draft() {
        requireDraftFixture();
        try (var stream = new ClassPathResource("diagnosis/questionnaire-draft-20261003.json").getInputStream()) {
            JsonNode root = mapper.readTree(stream);
            if (root.path("approved").asBoolean(true) || root.path("translationsApproved").asBoolean(true)) throw unavailable();
            List<DiagnosisQuestion> questions = new ArrayList<>();
            var ids = new HashSet<String>(); var counts = new EnumMap<DiagnosisAxis,Integer>(DiagnosisAxis.class);
            var ties = new ArrayList<DiagnosisTieQuestion>();
            var descriptions = new EnumMap<DiagnosisAxis, Map<String,String>>(DiagnosisAxis.class);
            for (JsonNode item : root.path("questions")) {
                String id = item.path("id").asText(); DiagnosisAxis axis = DiagnosisAxis.valueOf(item.path("axis").asText());
                int polarity = item.path("polarity").asInt();
                if (!ids.add(id) || id.isBlank() || Math.abs(polarity) != 1) throw unavailable();
                Map<String,String> texts = localeText(item.path("text"));
                questions.add(new DiagnosisQuestion(id, axis, polarity, texts));
                counts.merge(axis, 1, Integer::sum);
                if (!descriptions.containsKey(axis)) {
                    // 保存当時の説明は残す。極ラベルの選択は文面の区切りから再構成しない。
                    if (polarity != 1) throw unavailable();
                    descriptions.put(axis, texts);
                }
            }
            if (questions.size() != 24 || counts.size() != 6 || counts.values().stream().anyMatch(n -> n != 4)) throw unavailable();
            if (!root.path("ties").isArray()) throw unavailable();
            var tieAxes = new HashSet<DiagnosisAxis>();
            for (JsonNode item : root.path("ties")) {
                DiagnosisAxis axis = DiagnosisAxis.valueOf(required(item, "axisId"));
                if (!tieAxes.add(axis)) throw unavailable();
                ties.add(new DiagnosisTieQuestion(axis, localeText(item.path("zero")), localeText(item.path("one"))));
            }
            if (!tieAxes.equals(java.util.Set.of(DiagnosisAxis.values()))) throw unavailable();
            return new Definition(SNAPSHOT_SCHEMA_VERSION, SnapshotApproval.DRAFT, required(root,"questionnaireVersion"), required(root,"scoringVersion"), questions, ties, explanation(), descriptions);
        } catch (IOException | IllegalArgumentException error) { throw unavailable(); }
    }
    /** 保存済み定義を既知の採点方式と登録された承認定義で検証し、現在版から旧設問を再構成しない。 */
    public void requireSnapshotReadable(Definition definition) {
        if (definition != null && definition.approval() == SnapshotApproval.APPROVED
                && approved.contains(definition)) return;
        if (definition == null || !SNAPSHOT_SCHEMA_VERSION.equals(definition.snapshotSchemaVersion())
                || !"signed-centered-v1".equals(definition.scoringVersion())
                || !"draft-20261003-v1".equals(definition.questionnaireVersion())
                || definition.approval() != SnapshotApproval.DRAFT) throw unavailable();
    }
    public void requireMutationAllowed(Definition definition) {
        requireSnapshotReadable(definition);
        // 正式版は登録された不変定義と完全一致済み。保存flagだけでは到達しない。
        if (definition.approval() == SnapshotApproval.APPROVED) return;
        requireDraftFixture();
    }
    private void requireDraftFixture() {
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) throw unavailable();
        boolean isolatedFixture = environment.acceptsProfiles(Profiles.of("ranch-isolated"))
                && environment.getProperty("ranch.diagnosis.draft-enabled", Boolean.class, false);
        if (!environment.acceptsProfiles(Profiles.of("dev", "test")) && !isolatedFixture) throw unavailable();
    }
    private Map<String,String> localeText(JsonNode node) {
        var result = new java.util.HashMap<String,String>();
        for (String locale : LOCALES) result.put(locale, required(node,locale));
        return Map.copyOf(result);
    }
    private String required(JsonNode node, String field) {
        JsonNode value=node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw unavailable();
        return value.textValue();
    }
    private static Map<String,String> explanation(){return Map.of("ja","保存した24問の回答と本人の同点選択から、六軸の傾向を表しています。", "en","Six tendencies based on your saved answers and tie choices.", "zh","根据保存的回答和本人同分选择呈现六个倾向。", "ko","저장된 답변과 본인의 동점 선택에 따른 여섯 가지 성향입니다。", "es","Seis tendencias según tus respuestas guardadas y decisiones de empate.", "de","Sechs Tendenzen aus deinen gespeicherten Antworten und Entscheidungen bei Gleichstand.");}
    private static BusinessException unavailable() { return new BusinessException(DiagnosisErrorCode.UNAVAILABLE); }
}
