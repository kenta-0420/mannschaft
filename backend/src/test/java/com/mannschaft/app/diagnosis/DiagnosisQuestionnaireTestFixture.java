package com.mannschaft.app.diagnosis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import com.mannschaft.app.diagnosis.service.DiagnosisQuestionnaireCatalog;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

/** 保存済み旧版を当時の実resourceから作る合成fixture。現在版へ差し替えない。 */
public final class DiagnosisQuestionnaireTestFixture {
    private DiagnosisQuestionnaireTestFixture() { }

    public static DiagnosisQuestionnaireCatalog.Definition legacy(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("diagnosis/questionnaire-draft-20261003.json").getInputStream()) {
            var root = mapper.readTree(stream);
            var questions = new ArrayList<DiagnosisQuestion>();
            var ties = new ArrayList<DiagnosisTieQuestion>();
            var descriptions = new EnumMap<DiagnosisAxis, Map<String, String>>(DiagnosisAxis.class);
            for (var question : root.path("questions")) {
                var value = mapper.treeToValue(question, DiagnosisQuestion.class);
                questions.add(value);
                descriptions.putIfAbsent(value.axis(), value.text());
            }
            for (var tie : root.path("ties")) ties.add(mapper.treeToValue(tie, DiagnosisTieQuestion.class));
            return new DiagnosisQuestionnaireCatalog.Definition("diagnosis-session-snapshot-v1",
                    DiagnosisQuestionnaireCatalog.SnapshotApproval.DRAFT, root.path("questionnaireVersion").asText(),
                    root.path("scoringVersion").asText(), questions, ties,
                    Map.of("ja", "保存した24問の回答と本人の同点選択から、六軸の傾向を表しています。",
                            "en", "Six tendencies based on your saved answers and tie choices.",
                            "zh", "根据保存的回答和本人同分选择呈现六个倾向。",
                            "ko", "저장된 답변과 본인의 동점 선택에 따른 여섯 가지 성향입니다。",
                            "es", "Seis tendencias según tus respuestas guardadas y decisiones de empate.",
                            "de", "Sechs Tendenzen aus deinen gespeicherten Antworten und Entscheidungen bei Gleichstand."),
                    descriptions);
        }
    }
}
