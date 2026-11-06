package com.mannschaft.app.ranch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import com.mannschaft.app.ranch.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** ranch_dinosaursの本人スコープ永続骨格。 */
@Entity
@Table(name = "ranch_dinosaurs")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RanchDinosaurEntity extends RanchEntity {
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "habitat", nullable = true, length = 8)
    @Enumerated(EnumType.STRING)
    private Habitat habitat;
    @Column(name = "species_key", nullable = true, length = 60)
    private String speciesKey;
    @Column(name = "variant_key", nullable = true, length = 32)
    private String variantKey;
    @Column(name = "species_catalog_version", nullable = true)
    private Long speciesCatalogVersion;
    @Column(name = "assignment_method", nullable = true, length = 30)
    @Enumerated(EnumType.STRING)
    private AssignmentMethod assignmentMethod;
    @Column(name = "selection_confirmed_at", nullable = true)
    private Instant selectionConfirmedAt;
    @Column(name = "assignment_rule_version", nullable = true, length = 80)
    private String assignmentRuleVersion;
    @Column(name = "assignment_result_id", nullable = true)
    private UUID assignmentResultId;
    @Column(name = "assignment_input_hash", nullable = true, length = 64)
    private String assignmentInputHash;
    @Column(name = "egg_started_at", nullable = false)
    private Instant eggStartedAt;
    @Column(name = "egg_ready_at", nullable = false)
    private Instant eggReadyAt;
    @Column(name = "egg_rule_snapshot", nullable = false, columnDefinition = "json")
    private String eggRuleSnapshot;
    @Column(name = "hatched_at", nullable = true)
    private Instant hatchedAt;
    @Column(name = "name", nullable = true, length = 160)
    private String name;
    @Column(name = "named_at", nullable = true)
    private Instant namedAt;
    @Column(name = "stage", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DinosaurStage stage;
    @Column(name = "xp", nullable = false)
    private long xp;
    @Column(name = "growth_rule_snapshot", nullable = false, columnDefinition = "json")
    private String growthRuleSnapshot;
    @Column(name = "affinity", nullable = false)
    private long affinity;
    @Column(name = "affinity_rule_snapshot", nullable = false, columnDefinition = "json")
    private String affinityRuleSnapshot;
    @Column(name = "version", nullable = false)
    private long version;

    public void confirmAssignment(AssignmentMethod method, Habitat selectedHabitat,
                                  String selectedSpeciesKey, String selectedVariantKey,
                                  long catalogVersion, String ruleVersion,
                                  UUID resultId, String inputHash, Instant now) {
        if (method == null || selectedHabitat == null || now == null
                || stage != DinosaurStage.EGG
                || selectionConfirmedAt != null) {
            throw new IllegalStateException("選定済みまたは卵以外です");
        }
        habitat = selectedHabitat;
        speciesKey = selectedSpeciesKey;
        variantKey = selectedVariantKey;
        speciesCatalogVersion = catalogVersion;
        assignmentMethod = method;
        selectionConfirmedAt = now;
        assignmentRuleVersion = ruleVersion;
        assignmentResultId = resultId;
        assignmentInputHash = inputHash;
        advanceVersion();
    }

    public void hatch(String permanentName, Instant now) {
        if (permanentName == null || permanentName.isBlank() || now == null) {
            throw new IllegalArgumentException("永久名と時刻は必須です");
        }
        if (stage != DinosaurStage.EGG || eggReadyAt == null || selectionConfirmedAt == null
                || now.isBefore(eggReadyAt) || now.isBefore(selectionConfirmedAt)) {
            throw new IllegalStateException("孵化条件を満たしていません");
        }
        name = permanentName;
        namedAt = now;
        hatchedAt = now;
        stage = DinosaurStage.BABY;
    }

    public void applyCareXp(long gain, long juvenileXp, long adultXp) {
        if (stage == DinosaurStage.EGG || stage == null) {
            throw new IllegalStateException("卵にcare XPを付与できません");
        }
        if (gain < 0 || juvenileXp <= 0 || adultXp <= juvenileXp) {
            throw new IllegalArgumentException("成長規則またはXPが不正です");
        }
        xp = Math.addExact(xp, gain);
        if (xp >= adultXp) {
            stage = DinosaurStage.ADULT;
        } else if (xp >= juvenileXp && stage == DinosaurStage.BABY) {
            stage = DinosaurStage.JUVENILE;
        }
    }

    public void addAffinity(long gain) {
        if (gain <= 0) {
            throw new IllegalArgumentException("親密度の加算値が不正です");
        }
        affinity = Math.addExact(affinity, gain);
    }

    public void advanceVersion() {
        version = Math.addExact(version, 1);
    }
}
