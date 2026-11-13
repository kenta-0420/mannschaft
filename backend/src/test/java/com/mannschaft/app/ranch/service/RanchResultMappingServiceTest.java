package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DBを追加参照せず、結果へ保存する対応identity・説明と旧JSON互換を検証する。 */
class RanchResultMappingServiceTest {
    private static final Map<String,String> TEXT = Map.of("ja", "恐竜・外見1の合成説明", "en", "Dinosaur appearance 1",
            "zh", "恐龙外观1", "ko", "공룡 외형 1", "es", "Dinosaurio aspecto 1", "de", "Dinosaurier Aussehen 1");

    @Test
    void 診断と出生の新結果は当時の対応identityと六言語説明を固定する() throws Exception {
        var registry = mock(RanchProductionMasterRegistry.class);
        var master = master();
        when(registry.current()).thenReturn(Optional.of(master));
        var service = new RanchResultMappingService(registry);
        var mapper = new ObjectMapper().findAndRegisterModules();
        for (DiagnosisMethod method : DiagnosisMethod.values()) {
            var frozen = service.freeze(result(method));
            assertThat(frozen.mappingVersion()).isEqualTo("mapping-v1");
            assertThat(frozen.speciesKey()).isEqualTo("stegosaurus");
            assertThat(frozen.variantKey()).isEqualTo("V1");
            assertThat(frozen.catalogVersion()).isEqualTo("20261108");
            assertThat(frozen.mappingDescriptionSnapshot()).isEqualTo(TEXT);
            String saved = mapper.writeValueAsString(frozen);
            when(registry.current()).thenReturn(Optional.empty());
            var readable = mapper.readValue(saved, DiagnosisResultSummary.class);
            assertThat(readable).isEqualTo(frozen);
            assertThat(saved).doesNotContain("dateOfBirth", "lastNameKana", "firstNameKana", "answers");
            when(registry.current()).thenReturn(Optional.of(master));
        }
    }

    @Test
    void 未登録時の従来結果を維持し登録版と非互換の結果は保存へ進めない() {
        var registry = mock(RanchProductionMasterRegistry.class);
        var service = new RanchResultMappingService(registry);
        var result = result(DiagnosisMethod.DIAGNOSIS);
        when(registry.current()).thenReturn(Optional.empty());
        assertThat(service.freeze(result)).isSameAs(result);
        var master = master();
        when(master.questionnaireVersion()).thenReturn("different-version");
        when(registry.current()).thenReturn(Optional.of(master));
        assertThatThrownBy(() -> service.freeze(result)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 追加fieldのない旧JSONはnullidentityのまま読める() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.valueToTree(result(DiagnosisMethod.DIAGNOSIS));
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) json;
        object.remove(java.util.List.of("speciesKey", "variantKey", "catalogVersion", "mappingDescriptionSnapshot"));
        var legacy = mapper.treeToValue(object, DiagnosisResultSummary.class);
        assertThat(legacy.mappingVersion()).isNull();
        assertThat(legacy.speciesKey()).isNull();
        assertThat(legacy.mappingDescriptionSnapshot()).isNull();
        assertThat(legacy.descriptionSnapshot()).isEqualTo(Map.of("ja", "保存説明"));
    }

    private RanchProductionMasterRegistry.RegisteredMaster master() {
        var master = mock(RanchProductionMasterRegistry.RegisteredMaster.class);
        var pair = new RanchProductionMasterRegistry.Pair("stegosaurus", "V1");
        when(master.version()).thenReturn("mapping-v1");
        when(master.catalogVersion()).thenReturn(20261108L);
        when(master.resultSchemaVersion()).thenReturn("diagnosis-result-v1");
        when(master.questionnaireVersion()).thenReturn("questions-v1");
        when(master.scoringVersion()).thenReturn("signed-centered-v1");
        when(master.birthNormalizationVersion()).thenReturn("nfkc-hepburn-v1");
        when(master.birthRuleVersion()).thenReturn("digit-reduce-v1");
        when(master.diagnosis("000000")).thenReturn(Optional.of(pair));
        when(master.birth(1, 9)).thenReturn(Optional.of(pair));
        when(master.diagnosisDescriptions()).thenReturn(Map.of("000000", TEXT));
        when(master.birthDescriptions()).thenReturn(Map.of(new RanchProductionMasterRegistry.BirthPair(1, 9), TEXT));
        return master;
    }

    private DiagnosisResultSummary result(DiagnosisMethod method) {
        return new DiagnosisResultSummary(UUID.randomUUID(), method, Instant.EPOCH, "diagnosis-result-v1",
                "questions-v1", "signed-centered-v1", "nfkc-hepburn-v1", "digit-reduce-v1", null, "000000",
                Map.of(), new DiagnosisNumberSummary(1, 9, 10, 18), Map.of("ja", "保存説明"), Map.of());
    }
}
