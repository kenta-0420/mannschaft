package com.mannschaft.app.diagnosis.dto;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisMethod;

/** 本人結果の不変説明。回答・氏名・カナ・生年月日・確認証跡を含めない。 */
public record DiagnosisResultSummary(UUID id, DiagnosisMethod method, Instant completedAt,
        String resultSchemaVersion, String questionnaireVersion, String scoringVersion,
        String normalizationVersion, String ruleVersion, String mappingVersion, String typeCode,
        Map<DiagnosisAxis,Integer> axes, DiagnosisNumberSummary numberSummary,
        Map<String,String> descriptionSnapshot,
        Map<DiagnosisAxis,Map<String,String>> axisDescriptions) {}
