package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisAxisSelection;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 既知6bitの完成結果に、同じ診断開始時の極ラベルを軸キーで結び付ける。 */
public final class DiagnosisAxisSelectionSnapshot {
    private DiagnosisAxisSelectionSnapshot() {}

    public static Map<DiagnosisAxis, DiagnosisAxisSelection> create(String typeCode,
            Map<DiagnosisAxis, Integer> scores, List<DiagnosisTieQuestion> poles) {
        if (typeCode == null || !typeCode.matches("[01]{6}") || scores == null
                || !scores.keySet().equals(Set.of(DiagnosisAxis.values())) || poles == null) throw invalid();
        var byAxis = new EnumMap<DiagnosisAxis, DiagnosisTieQuestion>(DiagnosisAxis.class);
        for (var pole : poles) {
            if (pole == null || pole.axisId() == null || !readable(pole.zero()) || !readable(pole.one())
                    || byAxis.put(pole.axisId(), pole) != null) throw invalid();
        }
        var selections = new EnumMap<DiagnosisAxis, DiagnosisAxisSelection>(DiagnosisAxis.class);
        for (var axis : DiagnosisAxis.values()) {
            Integer score = scores.get(axis);
            var pole = byAxis.get(axis);
            int side = typeCode.charAt(axis.ordinal()) - '0';
            if (score == null || pole == null || score < -8 || score > 8
                    || (score != 0 && side != (score > 0 ? 1 : 0))) throw invalid();
            // ゼロ点では完成typeCodeの本人二択を使い、スコアから0側へ寄せない。
            selections.put(axis, new DiagnosisAxisSelection(side, pole.zero(), pole.one()));
        }
        return Map.copyOf(selections);
    }

    public static boolean needsSupplement(DiagnosisResultSummary saved) {
        return saved.method() == DiagnosisMethod.DIAGNOSIS && saved.axisSelections() == null
                && "diagnosis-result-v1".equals(saved.resultSchemaVersion())
                && "signed-centered-v1".equals(saved.scoringVersion())
                && saved.questionnaireVersion() != null && !saved.questionnaireVersion().isBlank();
    }

    /** 補足不能な旧結果は元の説明を残す。新しいmasterへ読み替えず、DBにも書き戻さない。 */
    public static DiagnosisResultSummary supplement(DiagnosisResultSummary saved,
            DiagnosisQuestionnaireCatalog.Definition definition) {
        if (!needsSupplement(saved) || definition == null
                || !DiagnosisQuestionnaireCatalog.SNAPSHOT_SCHEMA_VERSION.equals(definition.snapshotSchemaVersion())
                || definition.approval() == null
                || !Objects.equals(saved.questionnaireVersion(), definition.questionnaireVersion())
                || !Objects.equals(saved.scoringVersion(), definition.scoringVersion())) return saved;
        Map<DiagnosisAxis, DiagnosisAxisSelection> selections;
        try {
            selections = create(saved.typeCode(), saved.axes(), definition.ties());
        } catch (IllegalArgumentException insufficientSnapshot) {
            // 表示補足の情報不足はnullable契約で返し、画面が欠落を明示する。
            return saved;
        }
        return new DiagnosisResultSummary(saved.id(), saved.method(), saved.completedAt(), saved.resultSchemaVersion(),
                saved.questionnaireVersion(), saved.scoringVersion(), saved.normalizationVersion(), saved.ruleVersion(),
                saved.mappingVersion(), saved.typeCode(), saved.axes(), saved.numberSummary(),
                saved.descriptionSnapshot(), saved.axisDescriptions(), selections);
    }

    private static boolean readable(Map<String, String> texts) {
        return texts != null && texts.get("ja") != null && !texts.get("ja").isBlank();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("保存結果の六軸表示情報が不足しています");
    }
}
