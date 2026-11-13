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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
        assertThat(RanchProductionMasterRegistry.validateStructure(source, VERSION, "a".repeat(64)).version())
                .isEqualTo(VERSION);
    }

    @Test
    void 一素材欠落と未承認とdev種を拒否する() {
        ObjectNode missing = complete();
        ((ArrayNode) missing.path("assets")).remove(511);
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(missing, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode unapproved = complete();
        unapproved.put("approved", false);
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(unapproved, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode dev = complete();
        ((ObjectNode) dev.path("catalog").get(0)).put("speciesKey", "DEV_TRICERATOPS");
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(dev, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syntheticApprovedMasterResolvesDiagnosisAndConfirmedBirthWithoutSourceContents() {
        var master = RanchProductionMasterRegistry.validateStructure(complete(), VERSION, "a".repeat(64));
        var rules = mock(RanchRuleProvider.class);
        when(rules.currentCareRule(any())).thenReturn(java.util.Optional.of(
                new RanchRuleProvider.CareRuleSnapshot(UUID.randomUUID(), "care-synthetic-v1",
                        20, 100, 60, 100, 1, 3, 6)));
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        when(diagnosis.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())).thenReturn(true);
        var resolver = new RanchVerifiedAssignmentResolver(null, null, null, diagnosis, rules,
                Clock.systemUTC(), availableVisuals());
        UUID diagnosisId = UUID.randomUUID();
        var diagnosisSummary = new DiagnosisResultSummary(diagnosisId, DiagnosisMethod.DIAGNOSIS,
                Instant.parse("2026-10-05T00:00:00Z"), "diagnosis-result-v1",
                "synthetic-questions-v1", "synthetic-scoring-v1", null, null, null,
                "000001", Map.of(), null, Map.of(), Map.of());
        var diagnosisRequest = new RanchAssignmentRequest(AssignmentMethod.DIAGNOSIS,
                null, diagnosisId, null, "1");
        diagnosisSummary = diagnosisSummary.withMapping(master.version(), "SPECIES_0", "VARIANT_1", "7", master.diagnosisDescriptions().get("000001"));
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
        birthSummary = birthSummary.withMapping(master.version(), "SPECIES_2", "VARIANT_3", "7", master.birthDescriptions().get(new RanchProductionMasterRegistry.BirthPair(2, 3)));
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

    @Test
    void unregisteredAssetPackCannotBecomePublicationReady() {
        var noRegistration = new RanchProductionMasterRegistry(new MockEnvironment(), json);
        assertThat(noRegistration.current()).isEmpty();
        var missingPack = new MockEnvironment()
                .withProperty("mannschaft.ranch.production-master.resource",
                        "ranch/approved/synthetic-complete-v1.json")
                .withProperty("mannschaft.ranch.production-master.version", VERSION)
                .withProperty("mannschaft.ranch.production-master.sha256", "a".repeat(64));
        assertThatThrownBy(() -> new RanchProductionMasterRegistry(missingPack, json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void numericCoercionAndOverflowCannotRegisterMaster() {
        ObjectNode textVersion = complete();
        textVersion.put("catalogVersion", "7");
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(
                textVersion, VERSION, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode overflow = complete();
        overflow.put("catalogVersion", new java.math.BigInteger("9223372036854775808"));
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(
                overflow, VERSION, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode birthText = complete();
        ((ObjectNode) birthText.path("birthMappings").get(0)).put("lifePathNumber", "1");
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(
                birthText, VERSION, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode assetNumber = complete();
        ((ObjectNode) assetNumber.path("assets").get(0)).put("assetKey", 17);
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(
                assetNumber, VERSION, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registeredSyntheticPackNeedsManifestAndEveryRealByteAndMatchingTuple() throws Exception {
        var registered = new RanchProductionMasterRegistry(registered("synthetic-pack-v1", null), json);
        assertThat(registered.current()).isPresent();
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        assertThat(new RanchPublicationReadiness(registered, diagnosis).current().careReady()).isFalse();
        var master = registered.current().orElseThrow();
        when(diagnosis.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())).thenReturn(true);
        assertThat(new RanchPublicationReadiness(registered, diagnosis).current().careReady()).isTrue();
        assertThatThrownBy(() -> new RanchProductionMasterRegistry(
                registered("synthetic-pack-v1", "0".repeat(64)), json))
                .isInstanceOf(RuntimeException.class);
        for (String broken : List.of("synthetic-pack-v1-missing",
                "synthetic-pack-v1-wrongbytes", "synthetic-pack-v1-tuple")) {
            assertThatThrownBy(() -> new RanchProductionMasterRegistry(registered(broken, null), json))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void 出生81入力が揃っても全64外見を覆わなければ登録を拒否する() {
        var source = complete();
        source.path("birthMappings").forEach(row -> {
            ((ObjectNode) row).put("speciesKey", "SPECIES_0").put("variantKey", "VARIANT_0");
        });

        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(source, VERSION,
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 対応版nullの旧診断結果は新規割当へ読み替えない() {
        var master = RanchProductionMasterRegistry.validateStructure(complete(), VERSION, "a".repeat(64));
        var rules = mock(RanchRuleProvider.class);
        when(rules.currentCareRule(any())).thenReturn(java.util.Optional.of(
                new RanchRuleProvider.CareRuleSnapshot(UUID.randomUUID(), "care-synthetic-v1",
                        20, 100, 60, 100, 1, 3, 6)));
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        when(diagnosis.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())).thenReturn(true);
        var resolver = new RanchVerifiedAssignmentResolver(null, null, null, diagnosis, rules, Clock.systemUTC(), availableVisuals());
        UUID id = UUID.randomUUID();
        var legacy = new DiagnosisResultSummary(id, DiagnosisMethod.DIAGNOSIS, Instant.EPOCH,
                "diagnosis-result-v1", master.questionnaireVersion(), master.scoringVersion(),
                null, null, null, "000001", Map.of(), null, Map.of(), Map.of());
        var request = new RanchAssignmentRequest(AssignmentMethod.DIAGNOSIS, null, id, null, "1");

        assertThatThrownBy(() -> resolver.resolveApproved(request, legacy, null, master))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
    }
    private RanchApprovedVisualsAvailability availableVisuals() {
        var availability = mock(RanchApprovedVisualsAvailability.class);
        when(availability.isAvailable()).thenReturn(true);
        return availability;
    }

    @Test
    void 保存identityの改変と明示公開なしの正式割当を拒否する() {
        var master = RanchProductionMasterRegistry.validateStructure(complete(), VERSION, "a".repeat(64));
        var rules = mock(RanchRuleProvider.class);
        when(rules.currentCareRule(any())).thenReturn(java.util.Optional.of(
                new RanchRuleProvider.CareRuleSnapshot(UUID.randomUUID(), "care-synthetic-v1", 20, 100, 60, 100, 1, 3, 6)));
        var diagnosis = mock(DiagnosisPublicationReadiness.class);
        when(diagnosis.approvedVersionAvailable(master.questionnaireVersion(), master.scoringVersion())).thenReturn(true);
        var resolver = new RanchVerifiedAssignmentResolver(null, null, null, diagnosis, rules,
                Clock.systemUTC(), availableVisuals());
        UUID id = UUID.randomUUID();
        var valid = new DiagnosisResultSummary(id, DiagnosisMethod.DIAGNOSIS, Instant.EPOCH,
                "diagnosis-result-v1", master.questionnaireVersion(), master.scoringVersion(), null, null,
                null, "000001", Map.of(), null, Map.of(), Map.of()).withMapping(VERSION,
                        "SPECIES_0", "VARIANT_1", "7", master.diagnosisDescriptions().get("000001"));
        var request = new RanchAssignmentRequest(AssignmentMethod.DIAGNOSIS, null, id, null, "1");
        assertThat(resolver.resolveApproved(request, valid, null, master).variantKey()).isEqualTo("VARIANT_1");
        var changed = valid.withMapping(VERSION, "SPECIES_0", "VARIANT_2", "7", valid.mappingDescriptionSnapshot());
        assertThatThrownBy(() -> resolver.resolveApproved(request, changed, null, master))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
        var closed = new RanchVerifiedAssignmentResolver(null, null, null, diagnosis, rules,
                Clock.systemUTC(), new RanchApprovedVisualsAvailability(false, null));
        assertThatThrownBy(() -> closed.resolveApproved(request, valid, null, master))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
    }

    @Test
    void 対応説明の一翻訳でも欠落したmasterを拒否する() {
        var source = complete();
        ((ObjectNode) source.path("mappings").get(0).path("mappingDescriptionSnapshot")).remove("en");
        assertThatThrownBy(() -> RanchProductionMasterRegistry.validateStructure(source, VERSION, "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockEnvironment registered(String packVersion, String overridePackHash)
            throws IOException, NoSuchAlgorithmException {
        return new MockEnvironment()
                .withProperty("mannschaft.ranch.production-master.resource",
                        "ranch/approved/synthetic-complete-v1.json")
                .withProperty("mannschaft.ranch.production-master.version", VERSION)
                .withProperty("mannschaft.ranch.production-master.sha256",
                        resourceHash("ranch/approved/synthetic-complete-v1.json"))
                .withProperty("mannschaft.ranch.production-master.asset-pack-version", packVersion)
                .withProperty("mannschaft.ranch.production-master.asset-pack-sha256",
                        overridePackHash == null ? resourceHash("ranch/approved/asset-packs/"
                                + packVersion + "/manifest.json") : overridePackHash);
    }

    private static String resourceHash(String path) throws IOException, NoSuchAlgorithmException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stream.readAllBytes()));
        }
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
        var text = json.createObjectNode();
        for (String locale : List.of("ja", "en", "zh", "ko", "es", "de")) text.put(locale, "synthetic mapping");
        root.path("mappings").forEach(row -> ((ObjectNode) row).set("mappingDescriptionSnapshot", text.deepCopy()));
        root.path("birthMappings").forEach(row -> ((ObjectNode) row).set("mappingDescriptionSnapshot", text.deepCopy()));
        return root;
    }
}
