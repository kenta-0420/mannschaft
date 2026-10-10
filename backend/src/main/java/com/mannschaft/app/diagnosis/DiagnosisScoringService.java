package com.mannschaft.app.diagnosis;

import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisScore;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保存された24設問を採点し、ゼロの軸だけ本人の二択で確定する。 */
@Service
public class DiagnosisScoringService {
    public DiagnosisScore score(List<DiagnosisQuestion> questions, Map<String, Integer> answers,
                                Map<DiagnosisAxis, Integer> tieAnswers) {
        if (questions == null || questions.size() != 24 || answers == null || tieAnswers == null) {
            throw invalid();
        }
        Map<DiagnosisAxis, Integer> scores = new EnumMap<>(DiagnosisAxis.class);
        Map<DiagnosisAxis, Integer> counts = new EnumMap<>(DiagnosisAxis.class);
        Set<String> ids = new HashSet<>();
        for (DiagnosisQuestion question : questions) {
            if (question == null || question.id() == null || question.id().isBlank()
                    || question.axis() == null || Math.abs(question.polarity()) != 1
                    || !ids.add(question.id())) {
                throw invalid();
            }
            Integer answer = answers.get(question.id());
            if (answer == null || answer < 1 || answer > 5) throw invalid();
            counts.merge(question.axis(), 1, Integer::sum);
            scores.merge(question.axis(), question.polarity() * (answer - 3), Integer::sum);
        }
        if (!ids.equals(answers.keySet())) throw invalid();
        for (DiagnosisAxis axis : DiagnosisAxis.values()) {
            if (counts.getOrDefault(axis, 0) != 4) throw invalid();
        }
        for (Map.Entry<DiagnosisAxis, Integer> tie : tieAnswers.entrySet()) {
            if (tie.getKey() == null || scores.get(tie.getKey()) != 0 || tie.getValue() == null
                    || (tie.getValue() != 0 && tie.getValue() != 1)) throw invalid();
        }
        List<DiagnosisAxis> tiedAxes = new ArrayList<>();
        StringBuilder code = new StringBuilder(6);
        boolean unresolved = false;
        for (DiagnosisAxis axis : DiagnosisAxis.values()) {
            int score = scores.get(axis);
            if (score == 0) {
                tiedAxes.add(axis);
                Integer chosen = tieAnswers.get(axis);
                if (chosen == null) unresolved = true;
                else code.append(chosen);
            } else {
                code.append(score > 0 ? '1' : '0');
            }
        }
        return new DiagnosisScore(scores, tiedAxes, unresolved ? null : code.toString());
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("診断の設問または回答が不正です");
    }
}
