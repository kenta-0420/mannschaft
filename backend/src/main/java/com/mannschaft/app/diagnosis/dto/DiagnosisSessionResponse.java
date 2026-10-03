package com.mannschaft.app.diagnosis.dto;

import java.util.List;
import java.util.UUID;
import com.mannschaft.app.diagnosis.DiagnosisStatus;

/** 本人セッションの保存された設問と途中回答。出生情報を含めない。 */
public record DiagnosisSessionResponse(UUID id, DiagnosisStatus status, String version, String answerRevision,
        String questionnaireVersion, String scoringVersion, List<DiagnosisQuestion> questions,
        List<DiagnosisAnswer> answers, List<DiagnosisTieQuestion> tieQuestions, UUID resultId) {}
