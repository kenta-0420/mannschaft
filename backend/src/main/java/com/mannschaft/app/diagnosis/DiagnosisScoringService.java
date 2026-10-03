package com.mannschaft.app.diagnosis;

import com.mannschaft.app.diagnosis.dto.DiagnosisQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisScore;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** 保存された設問スナップショットを採点する。未実装の試練用骨格。 */
@Service
public class DiagnosisScoringService {
    public DiagnosisScore score(List<DiagnosisQuestion> questions, Map<String, Integer> answers,
                                Map<DiagnosisAxis, Integer> tieAnswers) {
        throw new UnsupportedOperationException("診断採点は未実装");
    }
}
