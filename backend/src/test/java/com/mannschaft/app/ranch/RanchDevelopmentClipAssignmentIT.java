package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.service.RanchAssignmentWriter;
import com.mannschaft.app.ranch.service.RanchDevelopmentClipCatalog;
import com.mannschaft.app.ranch.service.RanchEnrollmentWriter;
import com.mannschaft.app.ranch.service.RanchPurgeService;
import com.mannschaft.app.ranch.service.RanchStateAssembler;
import com.mannschaft.app.ranch.service.RanchVerifiedAssignmentResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLIP03/04: 実Bean・MySQL・commitで新規選定と既存ACKの凍結を観測する。 */
@TestPropertySource(properties = {"mannschaft.ranch.development-fixtures=true",
        "mannschaft.ranch.development-clips=true", "mannschaft.ranch.development-visuals=true"})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchDevelopmentClipAssignmentIT extends AbstractMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2032-01-06T12:00:00Z");
    private static final RanchStateAssembler.ExternalProjection PROJECTION =
            new RanchStateAssembler.ExternalProjection(false, "DISABLED", false,
                    false, null, null, List.of());
    @Autowired private RanchVerifiedAssignmentResolver resolver;
    @Autowired private RanchDevelopmentClipCatalog catalog;
    @Autowired private RanchAssignmentWriter writer;
    @Autowired private RanchEnrollmentWriter enrollment;
    @Autowired private RanchDinosaurRepository dinosaurs;
    @Autowired private RanchCommandRepository commands;
    @Autowired private UserRepository users;
    @Autowired private RanchPurgeService purge;
    @Autowired private com.mannschaft.app.ranch.service.RanchDevelopmentVisualCatalog legacy;
    @Autowired private com.mannschaft.app.ranch.service.RanchProductionMasterRegistry production;
    @Autowired private com.mannschaft.app.diagnosis.service.DiagnosisPublicationReadiness diagnosis;
    @Autowired private com.mannschaft.app.ranch.service.RanchRuleProvider rules;
    @Autowired private com.mannschaft.app.ranch.repository.RanchOperationalControlRepository controls;
    @Autowired private com.mannschaft.app.ranch.repository.RanchCareRuleRepository careRules;
    @Autowired private org.springframework.core.env.Environment environment;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private java.time.Clock clock;
    private final List<Long> ownUsers = new ArrayList<>();

    @AfterEach
    void 自分のfixtureだけ削除する() {
        for (Long id : ownUsers) {
            purge.purgeUser(id);
            users.deleteById(id);
        }
    }

    @Test
    void 既存ランダム選定が各生息地のv3を保存し再送は元ACKを返す() {
        assertThat(resolver.availableMethods()).containsExactly(AssignmentMethod.HABITAT_RANDOM);
        for (Habitat habitat : Habitat.values()) {
            Long id = enroll();
            var request = request(habitat, "0");
            var selected = resolver.resolve(id, request, null, null);
            assertThat(selected.catalogVersion()).isEqualTo(3L);
            assertThat(selected.habitat()).isEqualTo(habitat);
            assertThat(selected.variantKey()).isEqualTo("DEV_BASE_16_CLIPS_V1");
            assertThat(catalog.candidates(habitat)).extracting(RanchDevelopmentClipCatalog.Candidate::speciesKey)
                    .contains(selected.speciesKey());
            UUID key = UUID.randomUUID();
            var first = writer.assign(id, key, request, selected, NOW.plusSeconds(1));
            assertThat(dinosaurs.findByUserId(id).orElseThrow().getSpeciesCatalogVersion()).isEqualTo(3L);
            assertThat(writer.assign(id, key, request, null, NOW.plusSeconds(10))).isEqualTo(first);
            assertThat(writer.savedReplay(id, key, request)).contains(first);
            assertThat(commands.countByUserId(id)).isEqualTo(2);
        }
    }

    @Test
    void v3有効でも正式masterなしの診断出生選定は拒否する() {
        for (AssignmentMethod method : List.of(AssignmentMethod.DIAGNOSIS, AssignmentMethod.BIRTH_STYLE)) {
            var request = new RanchAssignmentRequest(method, null, UUID.randomUUID(), null, "0");
            assertThatThrownBy(() -> resolver.resolve(1L, request, null, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
        }
    }

    @Test
    void v3無効なら旧v2のLAND選定だけを維持する() {
        var disabled = new RanchDevelopmentClipCatalog(false, environment, json);
        var legacyResolver = new RanchVerifiedAssignmentResolver(legacy, disabled, production, diagnosis, rules, clock);
        var selection = legacyResolver.resolve(1L, request(Habitat.LAND, "0"), null, null);
        assertThat(selection.catalogVersion()).isEqualTo(2L);
        assertThat(selection.speciesKey()).isEqualTo("DEV_TRICERATOPS");
        assertThat(selection.variantKey()).isEqualTo("DEV_ORANGE_96_WALK_V1");
        for (Habitat habitat : List.of(Habitat.SEA, Habitat.AIR)) {
            assertThatThrownBy(() -> legacyResolver.resolve(1L, request(habitat, "0"), null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void v3有効でもcare規則なしなら選定できない() {
        // 実DBの運営OFFをこのテストTXだけに適用し、自動rollbackで元の行を復元する。
        controls.saveAndFlush(com.mannschaft.app.ranch.entity.RanchOperationalControlEntity.builder()
                .id(1).careEnabled(false).shopEnabled(false).deliveryPaused(true)
                .version(0).createdAt(NOW).updatedAt(NOW).build());
        var realRules = new com.mannschaft.app.ranch.service.ConfiguredRanchRuleProvider(
                false, environment, controls, careRules, clock);
        var unavailable = new RanchVerifiedAssignmentResolver(legacy, catalog, production, diagnosis, realRules, clock);
        assertThat(unavailable.availableMethods()).isEmpty();
        assertThatThrownBy(() -> unavailable.resolve(1L, request(Habitat.SEA, "0"), null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
    }

    @Test
    void v3有効でも既存v2選定済み卵と孵化個体を再割当せず元ACKを保持する() {
        for (boolean hatched : List.of(false, true)) {
            Long id = enroll();
            UUID oldKey = UUID.randomUUID();
            var originalRequest = request(Habitat.LAND, "0");
            var oldSelection = new com.mannschaft.app.ranch.service.RanchAssignmentResolver.Selection(
                    AssignmentMethod.HABITAT_RANDOM, Habitat.LAND, "DEV_TRICERATOPS",
                    "DEV_ORANGE_96_WALK_V1", 2L, "ranch-development-v1", null, "a".repeat(64));
            var original = writer.assign(id, oldKey, originalRequest, oldSelection, NOW.plusSeconds(1));
            var before = dinosaurs.findByUserId(id).orElseThrow();
            UUID dinosaurId = before.getId();
            if (hatched) {
                before.hatch("保存済み個体", NOW.plusSeconds(8 * 24 * 60 * 60));
                dinosaurs.saveAndFlush(before);
            }
            var retry = request(Habitat.SEA, "1");
            var replacement = resolver.resolve(id, retry, null, null);
            assertThatThrownBy(() -> writer.assign(id, UUID.randomUUID(), retry, replacement,
                    NOW.plusSeconds(9 * 24 * 60 * 60))).isInstanceOf(BusinessException.class);
            var after = dinosaurs.findByUserId(id).orElseThrow();
            assertThat(after.getId()).isEqualTo(dinosaurId);
            assertThat(after.getSpeciesKey()).isEqualTo("DEV_TRICERATOPS");
            assertThat(after.getVariantKey()).isEqualTo("DEV_ORANGE_96_WALK_V1");
            assertThat(after.getSpeciesCatalogVersion()).isEqualTo(2L);
            assertThat(after.getStage()).isEqualTo(hatched ? DinosaurStage.BABY : DinosaurStage.EGG);
            assertThat(writer.savedReplay(id, oldKey, originalRequest)).contains(original);
            assertThat(commands.countByUserId(id)).isEqualTo(2);
        }
    }

    private Long enroll() {
        Long id = users.saveAndFlush(RanchTestFixture.user()).getId();
        ownUsers.add(id);
        enrollment.enroll(id, UUID.randomUUID(), NOW, PROJECTION);
        return id;
    }

    private RanchAssignmentRequest request(Habitat habitat, String version) {
        return new RanchAssignmentRequest(AssignmentMethod.HABITAT_RANDOM, habitat, null, null, version);
    }
}
