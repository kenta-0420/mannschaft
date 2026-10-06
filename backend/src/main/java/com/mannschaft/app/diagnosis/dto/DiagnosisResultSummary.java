package com.mannschaft.app.diagnosis.dto;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import io.swagger.v3.oas.annotations.media.Schema;

/** 本人結果の不変説明。回答・氏名・カナ・生年月日・確認証跡を含めない。 */
public record DiagnosisResultSummary(UUID id, DiagnosisMethod method, Instant completedAt,
        String resultSchemaVersion, String questionnaireVersion, String scoringVersion,
        String normalizationVersion, String ruleVersion, String mappingVersion, String typeCode,
        Map<DiagnosisAxis,Integer> axes, DiagnosisNumberSummary numberSummary,
        Map<String,String> descriptionSnapshot,
        Map<DiagnosisAxis,Map<String,String>> axisDescriptions,
        @Schema(description = "保存時の六軸の極ラベルと本人の選択側。旧結果やBIRTH_STYLEではnullを許す",
                types = {"object", "null"}, nullable = true)
        Map<DiagnosisAxis,DiagnosisAxisSelection> axisSelections) {
    /** 既存の結果fixtureと呼出元は追加表示情報を持たない旧形のまま利用できる。 */
    public DiagnosisResultSummary(UUID id, DiagnosisMethod method, Instant completedAt,
            String resultSchemaVersion, String questionnaireVersion, String scoringVersion,
            String normalizationVersion, String ruleVersion, String mappingVersion, String typeCode,
            Map<DiagnosisAxis,Integer> axes, DiagnosisNumberSummary numberSummary,
            Map<String,String> descriptionSnapshot, Map<DiagnosisAxis,Map<String,String>> axisDescriptions) {
        this(id, method, completedAt, resultSchemaVersion, questionnaireVersion, scoringVersion,
                normalizationVersion, ruleVersion, mappingVersion, typeCode, axes, numberSummary,
                descriptionSnapshot, axisDescriptions, null);
    }
}
