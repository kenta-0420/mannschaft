package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.diagnosis.service.DiagnosisApprovedQuestionnaireRegistry;
import com.mannschaft.app.diagnosis.service.DiagnosisQuestionnaireCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 採択済み原稿と明示64/81表を正式512素材の実bytesへ接続する登録回帰。公開操作は行わない。 */
class RanchFormalMappingResourceTest {
    private static final String MASTER = "ranch/approved/ranch-mapping-20261108-v1.json";
    private static final String QUESTIONS = "diagnosis/approved/diagnosis-20261108-v1.json";
    private static final String PACK = "ranch/approved/asset-packs/ranch-approved-20261108-v1/manifest.json";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 採択済み64対応と出生81交点が正式512tupleの実素材に整合登録される() throws Exception {
        var registry = new RanchProductionMasterRegistry(environment(), json);
        var master = registry.current().orElseThrow();
        assertThat(master.catalogVersion()).isEqualTo(20261108L);
        assertThat(master.assetPackVersion()).isEqualTo("ranch-approved-20261108-v1");
        var fixed64 = json.readTree(Files.readString(Path.of("..", "docs", "features",
                "F04.7_dinosaur_ranch", "diagnosis64_species_mapping_v1.json")));
        for (var row : fixed64.path("mappings")) {
            assertThat(master.diagnosis(row.path("typeCode").asText())).contains(
                    new RanchProductionMasterRegistry.Pair(row.path("speciesKey").asText(), row.path("variantKey").asText()));
        }
        Set<RanchProductionMasterRegistry.Pair> birthPairs = new HashSet<>();
        var fixed81 = json.readTree(Files.readString(Path.of("..", "docs", "features",
                "F04.7_dinosaur_ranch", "birth81_species_mapping_v1.json")));
        for (var row : fixed81.path("mappings")) {
            assertThat(master.birth(row.path("lifePathNumber").asInt(), row.path("nameNumber").asInt()))
                    .contains(new RanchProductionMasterRegistry.Pair(row.path("speciesKey").asText(),
                            row.path("variantKey").asText()));
        }
        for (int life = 1; life <= 9; life++) {
            for (int name = 1; name <= 9; name++) birthPairs.add(master.birth(life, name).orElseThrow());
        }
        assertThat(birthPairs).hasSize(64).containsExactlyInAnyOrderElementsOf(master.byTypeCode().values());
        assertThat(master.birthDescriptions()).hasSize(81);
        assertThat(master.diagnosisDescriptions()).hasSize(64);
        for (var description : master.birthDescriptions().values()) {
            assertThat(description.keySet()).containsExactlyInAnyOrder("ja", "en", "zh", "ko", "es", "de");
        }
        var readiness = new RanchPublicationReadiness(registry,
                new com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness(
                        new DiagnosisApprovedQuestionnaireRegistry(environment(), json)));
        assertThat(new RanchApprovedVisualsAvailability(false, readiness).isAvailable()).isFalse();
        assertThat(new RanchApprovedVisualsAvailability(true, readiness).isAvailable()).isTrue();
        assertThat(hash(PACK)).isEqualTo("62d2d045e480bab986619410168bfef145989937a2b994c8cc4e3eec9346d26b");
        // 実PNG280355204bytesは旧256MiBを超える。v2専用320MiBで原本bytesをそのまま登録できる。
        assertThat(pngBytes()).isEqualTo(280355204L).isBetween(256L * 1024 * 1024, 320L * 1024 * 1024);
    }

    @Test
    void 正式質問版は採択済み24問と六軸同点質問の216翻訳値を無改変で保持する() throws Exception {
        var env = environment();
        var registry = new DiagnosisApprovedQuestionnaireRegistry(env, json);
        var catalog = new DiagnosisQuestionnaireCatalog(json, env, registry);
        var current = catalog.forStart();
        assertThat(current.questionnaireVersion()).isEqualTo("diagnosis-20261108-v1");
        assertThat(current.scoringVersion()).isEqualTo("signed-centered-v1");
        var source = json.readTree(bytes("diagnosis/questionnaire-draft-20261010.json"));
        JsonNode questions = json.valueToTree(current.questions());
        JsonNode ties = json.valueToTree(current.ties());
        assertThat(questions).isEqualTo(source.path("questions"));
        assertThat(ties).isEqualTo(source.path("ties"));
        assertThat(current.questions()).hasSize(24);
        assertThat(current.ties()).hasSize(6);
        assertThat(current.approval()).isEqualTo(DiagnosisQuestionnaireCatalog.SnapshotApproval.APPROVED);
    }

    private MockEnvironment environment() throws Exception {
        return new MockEnvironment()
                .withProperty("mannschaft.ranch.production-master.resource", MASTER)
                .withProperty("mannschaft.ranch.production-master.version", "ranch-mapping-20261108-v1")
                .withProperty("mannschaft.ranch.production-master.sha256", hash(MASTER))
                .withProperty("mannschaft.ranch.production-master.asset-pack-version", "ranch-approved-20261108-v1")
                .withProperty("mannschaft.ranch.production-master.asset-pack-sha256", hash(PACK))
                .withProperty("mannschaft.diagnosis.approved-catalog.resource", QUESTIONS)
                .withProperty("mannschaft.diagnosis.approved-catalog.version", "diagnosis-20261108-v1")
                .withProperty("mannschaft.diagnosis.approved-catalog.sha256", hash(QUESTIONS));
    }

    private byte[] bytes(String resource) throws Exception {
        try (var stream = new ClassPathResource(resource).getInputStream()) { return stream.readAllBytes(); }
    }

    private long pngBytes() throws Exception {
        var manifest = json.readTree(bytes(PACK));
        Set<String> paths = new HashSet<>();
        for (var entry : manifest.path("entries")) {
            paths.add(entry.path("filePath").asText());
            paths.add(entry.path("fallbackPath").asText());
            entry.path("clips").forEach(clip -> paths.add(clip.path("filePath").asText()));
        }
        assertThat(paths).hasSize(1922);
        long total = 0;
        for (String path : paths) total += new ClassPathResource(PACK.replace("manifest.json", "") + path).contentLength();
        return total;
    }

    private String hash(String resource) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(resource)));
    }
}
