package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.dto.DiagnosisAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** session内部JSONを不変DTOへ復元する。Entityは公開境界を越えない。 */
@Service
@RequiredArgsConstructor
public class DiagnosisSessionSnapshotCodec {
    private final ObjectMapper mapper;
    public record Answers(List<DiagnosisAnswer> answers, Map<DiagnosisAxis,Integer> tieAnswers) {
        public Answers { answers = List.copyOf(answers); tieAnswers = Map.copyOf(tieAnswers); }
    }
    public String encodeDefinition(DiagnosisQuestionnaireCatalog.Definition value) { return encode(value); }
    public String encodeAnswers(Answers value) { return encode(value); }
    public DiagnosisQuestionnaireCatalog.Definition definition(String value) {
        return decode(value, DiagnosisQuestionnaireCatalog.Definition.class);
    }
    public Answers answers(String value) { return decode(value, Answers.class); }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw unavailable(); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw unavailable(); }
    }
    private static BusinessException unavailable() { return new BusinessException(DiagnosisErrorCode.UNAVAILABLE); }
}
