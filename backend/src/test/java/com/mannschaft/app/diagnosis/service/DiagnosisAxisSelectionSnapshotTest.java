package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.config.JacksonConfig;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 6bitと保存極ラベルの結合を実値で検証する。現在master・文面の区切りに依存しない。 */
class DiagnosisAxisSelectionSnapshotTest {
    private List<DiagnosisTieQuestion> poles() {
        var values = new ArrayList<DiagnosisTieQuestion>();
        for (var axis : DiagnosisAxis.values()) {
            values.add(new DiagnosisTieQuestion(axis, Map.of("ja", axis + " zero without separator"),
                    Map.of("ja", axis + " one without separator")));
        }
        Collections.reverse(values);
        return values;
    }

    private Map<DiagnosisAxis, Integer> scores() {
        var values = new EnumMap<DiagnosisAxis, Integer>(DiagnosisAxis.class);
        for (var axis : DiagnosisAxis.values()) values.put(axis, 0);
        return values;
    }

    @Test void 極ラベル逆順とゼロ点でも本人の同点選択1を保持する() {
        var selections = DiagnosisAxisSelectionSnapshot.create("010101", scores(), poles());
        assertThat(selections.get(DiagnosisAxis.FAMILIAR_NEW).side()).isZero();
        assertThat(selections.get(DiagnosisAxis.FOCUS_VARIETY).side()).isEqualTo(1);
        assertThat(selections.get(DiagnosisAxis.FOCUS_VARIETY).one().get("ja"))
                .isEqualTo("FOCUS_VARIETY one without separator");
        assertThat(selections.get(DiagnosisAxis.NOTICE).side()).isEqualTo(1);
    }

    @Test void 全0と全1の六軸を維持する() {
        for (int side : new int[]{0, 1}) {
            var selections = DiagnosisAxisSelectionSnapshot.create(Integer.toString(side).repeat(6), scores(), poles());
            assertThat(selections).hasSize(6);
            assertThat(selections.values()).allSatisfy(selection -> assertThat(selection.side()).isEqualTo(side));
        }
    }

    @Test void 追加表示情報の無い旧JSONを復元できる() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var old = new DiagnosisResultSummary(UUID.randomUUID(), DiagnosisMethod.DIAGNOSIS, Instant.EPOCH,
                "diagnosis-result-v1", "draft-20261003-v1", "signed-centered-v1", null, null, null,
                "010101", scores(), null, Map.of("ja", "保存した説明"), Map.of(), null);
        var json = mapper.valueToTree(old);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("axisSelections");
        var restored = mapper.treeToValue(json, DiagnosisResultSummary.class);
        assertThat(restored.axisSelections()).isNull();
        assertThat(restored.typeCode()).isEqualTo("010101");
        assertThat(restored.descriptionSnapshot()).isEqualTo(old.descriptionSnapshot());
    }
    /** 変更前のrecordと同じ14fieldの読取形を固定し、primary mapperで新JSONを実際に読む。 */
    record LegacyResult(UUID id, DiagnosisMethod method, Instant completedAt,
            String resultSchemaVersion, String questionnaireVersion, String scoringVersion,
            String normalizationVersion, String ruleVersion, String mappingVersion, String typeCode,
            Map<DiagnosisAxis,Integer> axes, DiagnosisNumberSummary numberSummary,
            Map<String,String> descriptionSnapshot, Map<DiagnosisAxis,Map<String,String>> axisDescriptions) {}

    @Test void 現行primaryMapperの旧record読取形で追加optionalを許容する() throws Exception {
        var mapper = new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());
        var value = new DiagnosisResultSummary(UUID.randomUUID(), DiagnosisMethod.DIAGNOSIS, Instant.EPOCH,
                "diagnosis-result-v1", "draft-20261003-v1", "signed-centered-v1", null, null, null,
                "010101", scores(), null, Map.of("ja", "保存した説明"), Map.of(),
                DiagnosisAxisSelectionSnapshot.create("010101", scores(), poles()));
        var restored = mapper.readValue(mapper.writeValueAsString(value), LegacyResult.class);
        assertThat(restored.typeCode()).isEqualTo(value.typeCode());
        assertThat(restored.descriptionSnapshot()).isEqualTo(value.descriptionSnapshot());
        assertThat(restored.resultSchemaVersion()).isEqualTo("diagnosis-result-v1");
    }
}
