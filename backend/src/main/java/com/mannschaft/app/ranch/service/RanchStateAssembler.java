package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.AssignmentSummary;
import com.mannschaft.app.ranch.dto.CareBudget;
import com.mannschaft.app.ranch.dto.DinosaurSummary;
import com.mannschaft.app.ranch.dto.EggSummary;
import com.mannschaft.app.ranch.dto.OwnerSummary;
import com.mannschaft.app.ranch.dto.RanchSettings;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import com.mannschaft.app.ranch.dto.WeekBudget;
import com.mannschaft.app.ranch.entity.RanchCareWeekBudgetEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchRoomPlacementEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 自domain行と認可済み外domain投影から完全RanchStateを純粋組立する。 */
@Component
@RequiredArgsConstructor
public class RanchStateAssembler {
    private final ObjectMapper json;
    private final RanchEggCalculator eggs = new RanchEggCalculator();

    public RanchState assemble(Long userId, Instant serverTime, ExternalProjection external,
                               RanchOwnerEntity owner, RanchDinosaurEntity dinosaur,
                               List<RanchRoomPlacementEntity> placements,
                               RanchCareWeekBudgetEntity careWeek,
                               Optional<RanchRuleProvider.CareRuleSnapshot> careRule) {
        return assemble(userId, serverTime, external, owner, dinosaur, placements,
                careWeek, careRule, Map.of());
    }

    public RanchState assemble(Long userId, Instant serverTime, ExternalProjection external,
                               RanchOwnerEntity owner, RanchDinosaurEntity dinosaur,
                               List<RanchRoomPlacementEntity> placements,
                               RanchCareWeekBudgetEntity careWeek,
                               Optional<RanchRuleProvider.CareRuleSnapshot> careRule,
                               Map<UUID, RoomSlotSummary.Decoration> decorations) {
        Objects.requireNonNull(userId, "本人IDは必須です");
        Instant now = Objects.requireNonNull(serverTime, "時刻は必須です")
                .truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(external, "認可済み外domain投影は必須です");
        Objects.requireNonNull(placements, "部屋行は必須です");
        Objects.requireNonNull(careRule, "care規則は必須です");
        Objects.requireNonNull(decorations);
        if (owner == null) {
            if (dinosaur != null || !placements.isEmpty() || careWeek != null) {
                throw inconsistent();
            }
            return new RanchState(careRule.isPresent() ? "AVAILABLE" : "UNAVAILABLE",
                    external.deliveryPaused(), external.rewardsStatus(),
                    external.shopAvailable(), null, null, null, null,
                    external.weekBudget(), List.of(), external.policyVersion(), now, null);
        }
        if (dinosaur == null || !owner.getUserId().equals(userId)
                || !dinosaur.getUserId().equals(userId)
                || !dinosaur.getOwnerId().equals(owner.getId())
                || placements.stream().anyMatch(slot -> !userId.equals(slot.getUserId())
                        || !owner.getId().equals(slot.getOwnerId()))
                || (careWeek != null && (!userId.equals(careWeek.getUserId())
                        || !owner.getId().equals(careWeek.getOwnerId())))) {
            throw inconsistent();
        }

        OwnerSummary ownerSummary = new OwnerSummary(owner.getId(), owner.getStatus(),
                Long.toString(owner.getBalance()), Long.toString(owner.getVersion()));
        RanchSettings settings = new RanchSettings(external.visible(), owner.getViewMode(),
                owner.getRenderStyle(), owner.getMotionMode(), owner.isSoundEnabled(),
                owner.getSoundVolume(), Long.toString(owner.getVersion()));
        EggSummary egg = null;
        String nextStageXp = null;
        if (dinosaur.getStage() == DinosaurStage.EGG) {
            JsonNode snapshot = parse(dinosaur.getEggRuleSnapshot());
            egg = new EggSummary(dinosaur.getEggStartedAt(), dinosaur.getEggReadyAt(),
                    eggs.crackStage(dinosaur.getEggStartedAt(), now,
                            number(snapshot, "smallCrackSeconds"),
                            number(snapshot, "wideCrackSeconds"),
                            number(snapshot, "durationSeconds")),
                    eggs.hatchReady(dinosaur.getEggReadyAt(),
                            dinosaur.getSelectionConfirmedAt(), now),
                    dinosaur.getHatchedAt());
        } else if (dinosaur.getStage() == DinosaurStage.BABY
                || dinosaur.getStage() == DinosaurStage.JUVENILE) {
            JsonNode growth = parse(dinosaur.getGrowthRuleSnapshot());
            nextStageXp = Long.toString(number(growth,
                    dinosaur.getStage() == DinosaurStage.BABY ? "juvenileXp" : "adultXp"));
        }
        DinosaurSummary dinosaurSummary = new DinosaurSummary(dinosaur.getId(),
                dinosaur.getSpeciesKey(), dinosaur.getVariantKey(), dinosaur.getHabitat(),
                dinosaur.getSpeciesCatalogVersion() == null ? null
                        : dinosaur.getSpeciesCatalogVersion().toString(),
                dinosaur.getStage(), dinosaur.getName(), dinosaur.getNamedAt(),
                Long.toString(dinosaur.getXp()), nextStageXp,
                Long.toString(dinosaur.getVersion()), egg, affinityBand(dinosaur));
        List<RoomSlotSummary> rooms = placements.stream()
                .map(slot -> new RoomSlotSummary(slot.getSlotKey(), slot.getInventoryId(),
                        Long.toString(slot.getVersion()), slot.getInventoryId() == null
                                ? null : decorations.get(slot.getInventoryId())))
                .toList();
        AssignmentSummary assignment = new AssignmentSummary(external.availableMethods(),
                dinosaur.getSelectionConfirmedAt() != null, dinosaur.getAssignmentMethod());
        CareBudget budget = careRule.map(rule -> careBudget(now, rule, careWeek)).orElse(null);
        return new RanchState(careRule.isPresent() ? "AVAILABLE" : "UNAVAILABLE",
                external.deliveryPaused(), external.rewardsStatus(),
                external.shopAvailable(), budget, ownerSummary, dinosaurSummary,
                settings, external.weekBudget(), rooms, external.policyVersion(), now,
                assignment);
    }

    private CareBudget careBudget(Instant now, RanchRuleProvider.CareRuleSnapshot rule,
                                  RanchCareWeekBudgetEntity persisted) {
        LocalDate monday = now.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant weekEnd = monday.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
        long cap = persisted == null ? rule.weeklyCapXp() : persisted.getWeeklyCapXp();
        long awarded = persisted == null ? 0 : persisted.getAwardedXp();
        long amount = rule.amountXp();
        String ruleVersion = rule.ruleVersion();
        if (persisted != null && !monday.equals(persisted.getWeekStartsOn())) {
            throw inconsistent();
        }
        if (persisted != null) {
            JsonNode frozen = parse(persisted.getRuleSnapshot());
            amount = number(frozen, "amountXp");
            JsonNode version = frozen.get("ruleVersion");
            if (version == null || !version.isTextual() || version.asText().isBlank()) {
                throw inconsistent();
            }
            ruleVersion = version.asText();
        }
        if (cap <= 0 || awarded < 0 || awarded > cap) {
            throw inconsistent();
        }
        return new CareBudget(monday, weekEnd, Long.toString(cap),
                Long.toString(awarded), Long.toString(cap - awarded),
                Long.toString(amount), ruleVersion);
    }

    private JsonNode parse(String snapshot) {
        try {
            return json.readTree(snapshot);
        } catch (Exception exception) {
            throw inconsistent();
        }
    }

    private String affinityBand(RanchDinosaurEntity dinosaur) {
        JsonNode frozen = parse(dinosaur.getAffinityRuleSnapshot());
        long warm = number(frozen, "warmAffinity");
        long close = number(frozen, "closeAffinity");
        if (warm <= 0 || close <= warm || dinosaur.getAffinity() < 0) throw inconsistent();
        return dinosaur.getAffinity() >= close ? "CLOSE"
                : dinosaur.getAffinity() >= warm ? "WARM" : "NEUTRAL";
    }

    private long number(JsonNode snapshot, String field) {
        JsonNode value = snapshot.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw inconsistent();
        }
        return value.longValue();
    }

    private BusinessException inconsistent() {
        return new BusinessException(RanchErrorCode.RANCH_008,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Auth後に各正規facadeから得た固定値。HTTP body/header由来値は渡さない。 */
    public record ExternalProjection(boolean deliveryPaused, String rewardsStatus,
                                     boolean shopAvailable, boolean visible,
                                     WeekBudget weekBudget, String policyVersion,
                                     List<AssignmentMethod> availableMethods) {
        public ExternalProjection {
            Objects.requireNonNull(rewardsStatus, "報酬状態は必須です");
            availableMethods = List.copyOf(availableMethods);
        }
    }
}
