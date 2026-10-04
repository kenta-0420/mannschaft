package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.auth.service.BirthStyleCalculator.BirthNumbers;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisNumberSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.Habitat;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 架空の完全coverageを検証するだけで、実際の64種承認登録や公開を行わない。 */
class RanchProductionMasterRegistryTest {
    private static final String VERSION = "synthetic-complete-v1";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 合成64組と512有限素材ならcoverageとして受理する() {
        ObjectNode source = complete();
        assertThat(RanchProductionMasterRegistry.validate(source, VERSION, "a".repeat(64)).version())
                .isEqualTo(VERSION);
    }

    @Test
    void 一素材欠落と未承認とdev種を拒否する() {
        ObjectNode missing = complete();
        ((ArrayNode) missing.path("assets")).remove(511);
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validate(missing, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode unapproved = complete();
        unapproved.put("approved", false);
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validate(unapproved, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode dev = complete();
        ((ObjectNode) dev.path("catalog").get(0)).put("speciesKey", "DEV_TRICERATOPS");
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validate(dev, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syntheticApprovedMasterResolvesDiagnosisAndConfirmedBirthWithoutSourceContents() {
        var master = RanchProductionMasterRegistry.validate(complete(), VERSION, "a".repeat(64));
        var rules = mock(RanchRuleProvider.class);
        when(rules.currentCareRule(any())).thenReturn(java.util.Optional.of(
                new RanchRuleProvider.CareRuleSnapshot(UUID.randomUUID(), "care-synthetic-v1",
                        20, 100, 60, 100, 1, 3, 6)));
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        when(diagnosis.approvedCatalogAvailable()).thenReturn(true);
        var resolver = new RanchVerifiedAssignmentResolver(null, null, diagnosis, rules,
                Clock.systemUTC());
        UUID diagnosisId = UUID.randomUUID();
        var diagnosisSummary = new DiagnosisResultSummary(diagnosisId, DiagnosisMethod.DIAGNOSIS,
                Instant.parse("2026-10-05T00:00:00Z"), "diagnosis-result-v1",
                "synthetic-questions-v1", "synthetic-scoring-v1", null, null, null,
                "000001", Map.of(), null, Map.of(), Map.of());
        var diagnosisRequest = new RanchAssignmentRequest(AssignmentMethod.DIAGNOSIS,
                null, diagnosisId, null, "1");
        var selected = resolver.resolveApproved(diagnosisRequest, diagnosisSummary, null, master);
        assertThat(selected.speciesKey()).isEqualTo("SPECIES_0");
        assertThat(selected.variantKey()).isEqualTo("VARIANT_1");
        assertThat(selected.catalogVersion()).isEqualTo(7);
        assertThat(selected.habitat()).isEqualTo(Habitat.LAND);

        UUID birthId = UUID.randomUUID();
        var numbers = new BirthNumbers(2, 3, 11, 12,
                "birth-normalization-v1", "birth-rule-v1");
        var birthSummary = new DiagnosisResultSummary(birthId, DiagnosisMethod.BIRTH_STYLE,
                Instant.parse("2026-10-05T00:00:00Z"), "diagnosis-result-v1",
                null, null, "birth-normalization-v1", "birth-rule-v1", null,
                null, Map.of(), new DiagnosisNumberSummary(2, 3, 11, 12), Map.of(), Map.of());
        var birthRequest = new RanchAssignmentRequest(AssignmentMethod.BIRTH_STYLE,
                null, birthId, UUID.randomUUID().toString(), "1");
        var birth = resolver.resolveApproved(birthRequest, birthSummary, numbers, master);
        assertThat(birth.speciesKey()).isEqualTo("SPECIES_2");
        assertThat(birth.variantKey()).isEqualTo("VARIANT_3");
        assertThat(birth.catalogVersion()).isEqualTo(7);

        var stale = new DiagnosisResultSummary(diagnosisId, DiagnosisMethod.DIAGNOSIS,
                diagnosisSummary.completedAt(), diagnosisSummary.resultSchemaVersion(),
                "old-questionnaire", diagnosisSummary.scoringVersion(), null, null, null,
                "000001", Map.of(), null, Map.of(), Map.of());
        assertThatThrownBy(() -> resolver.resolveApproved(diagnosisRequest, stale, null, master))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
    }

    private ObjectNode complete() {
        ObjectNode root = json.createObjectNode().put("approved", true).put("version", VERSION);
        root.put("catalogVersion", 7);
        root.putObject("compatibility")
                .put("resultSchemaVersion", "diagnosis-result-v1")
                .put("questionnaireVersion", "synthetic-questions-v1")
                .put("scoringVersion", "synthetic-scoring-v1")
                .put("birthNormalizationVersion", "birth-normalization-v1")
                .put("birthRuleVersion", "birth-rule-v1");
        ArrayNode birthMappings = root.putArray("birthMappings");
        for (int life = 1; life <= 9; life++) {
            for (int name = 1; name <= 9; name++) {
                int type = ((life - 1) * 9 + name - 1) % 64;
                birthMappings.addObject().put("lifePathNumber", life).put("nameNumber", name)
                        .put("speciesKey", "SPECIES_" + (type / 4))
                        .put("variantKey", "VARIANT_" + (type % 4));
            }
        }
        ArrayNode catalog = root.putArray("catalog");
        ArrayNode mappings = root.putArray("mappings");
        ArrayNode assets = root.putArray("assets");
        ObjectNode pools = root.putObject("randomPools");
        ArrayNode land = pools.putArray("LAND");
        ArrayNode sea = pools.putArray("SEA");
        ArrayNode air = pools.putArray("AIR");
        for (int species = 0; species < 16; species++) {
            String speciesKey = "SPECIES_" + species;
            String habitat = switch (species % 3) {
                case 0 -> "LAND";
                case 1 -> "SEA";
                default -> "AIR";
            };
            ArrayNode pool = switch (habitat) {
                case "LAND" -> land;
                case "SEA" -> sea;
                default -> air;
            };
            ObjectNode speciesRow = catalog.addObject().put("speciesKey", speciesKey)
                    .put("habitat", habitat);
            ArrayNode variants = speciesRow.putArray("variants");
            for (int variant = 0; variant < 4; variant++) {
                String variantKey = "VARIANT_" + variant;
                variants.add(variantKey);
                pool.addObject().put("speciesKey", speciesKey).put("variantKey", variantKey);
                int type = species * 4 + variant;
                mappings.addObject().put("typeCode", String.format("%6s",
                                Integer.toBinaryString(type)).replace(' ', '0'))
                        .put("speciesKey", speciesKey).put("variantKey", variantKey);
                for (String stage : List.of("EGG", "BABY", "JUVENILE", "ADULT")) {
                    for (String style : List.of("PIXEL", "PAINT_2D")) {
                        assets.addObject().put("speciesKey", speciesKey)
                                .put("variantKey", variantKey).put("stage", stage)
                                .put("style", style).put("assetKey", "asset_" + type + "_" + stage + "_" + style)
                                .put("reactionKey", "EGG".equals(stage) ? "EGG_TOUCH" : "DINOSAUR_TOUCH")
                                .put("staticFallbackKey", "still_" + type + "_" + stage + "_" + style)
                                .put("sha256", "a".repeat(64))
                                .put("staticFallbackSha256", "b".repeat(64));
                    }
                }
            }
        }
        return root;
    }
}
