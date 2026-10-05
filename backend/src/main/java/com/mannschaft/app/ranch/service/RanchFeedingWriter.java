package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.FeedingResult;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.entity.RanchAffinityUnitEntity;
import com.mannschaft.app.ranch.entity.RanchCareWeekBudgetEntity;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 無料careの週枠・親密度・不変台帳を同じ牧場取引で確定する。 */
@Service
@RequiredArgsConstructor
public class RanchFeedingWriter {
    private static final String TYPE = "FEEDING";
    private static final String RESOURCE = "/api/v1/me/ranch/feeding";
    private static final String CARE_KIND = "FREE_BASIC";
    private static final String AFFINITY_KIND = "FEED";

    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchCareWeekBudgetRepository budgets;
    private final RanchAffinityUnitRepository affinities;
    private final RanchCommandRepository commands;
    private final RanchPointLedgerRepository ledger;
    private final RanchRuleProvider rules;
    private final ObjectMapper json;
    private final RanchCareCalculator calculator = new RanchCareCalculator();
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FeedingResult feed(Long userId, UUID key, RanchVersionRequest request,
                              Instant serverTime) {
        return feedOutcome(userId, key, request, serverTime).result();
    }

    /** 保存済み成功と初回を同じTX内で判別する。公開DTOにはtransport状態を混ぜない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FeedOutcome feedOutcome(Long userId, UUID key, RanchVersionRequest request,
                                   Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        byte[] hash = hasher.hash(TYPE, RESOURCE, null, json.valueToTree(request));
        var previous = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (previous.isPresent()) {
            RanchCommandEntity command = previous.orElseThrow();
            if (!TYPE.equals(command.getCommandType())
                    || !Arrays.equals(hash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            return new FeedOutcome(decode(command.getResultJson(), FeedingResult.class), false);
        }

        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        if (!rules.careEnabled()) throw unavailable();
        RanchRuleProvider.CareRuleSnapshot current = rules.currentCareRule(now)
                .orElseThrow(this::unavailable);
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElseThrow(this::notFound);
        if (owner.getStatus() != ParticipationStatus.ACTIVE) throw conflict();
        if (owner.getVersion() != version(request.version())) throw conflict();
        RanchDinosaurEntity dinosaur = dinosaurs.findByUserId(userId)
                .orElseThrow(this::inconsistent);
        if (!owner.getId().equals(dinosaur.getOwnerId())) throw inconsistent();
        if (dinosaur.getStage() == DinosaurStage.EGG) throw conflict();

        LocalDate week = calculator.weekStartsOn(now);
        RanchCareWeekBudgetEntity budget = budgets.findByUserIdAndWeekStartsOn(userId, week)
                .orElse(null);
        long amount;
        long cap;
        String ruleVersion;
        if (budget == null) {
            if (current.amountXp() <= 0 || current.weeklyCapXp() <= 0
                    || current.juvenileXp() <= 0
                    || current.adultXp() <= current.juvenileXp()) {
                throw inconsistent();
            }
            amount = current.amountXp();
            cap = current.weeklyCapXp();
            ruleVersion = current.ruleVersion();
            budget = RanchCareWeekBudgetEntity.builder()
                    .ownerId(owner.getId()).userId(userId).weekStartsOn(week)
                    .ruleId(current.ruleId()).ruleSnapshot(encode(Map.of(
                            "ruleVersion", ruleVersion, "amountXp", amount,
                            "weeklyCapXp", cap)))
                    .weeklyCapXp(cap).awardedXp(0).version(0).createdAt(now).build();
        } else {
            if (!owner.getId().equals(budget.getOwnerId())) throw inconsistent();
            JsonNode frozen = parse(budget.getRuleSnapshot());
            amount = positive(frozen, "amountXp");
            cap = positive(frozen, "weeklyCapXp");
            ruleVersion = text(frozen, "ruleVersion");
            if (cap != budget.getWeeklyCapXp()) throw inconsistent();
        }

        long gained = calculator.gainedXp(amount, cap, budget.getAwardedXp());
        JsonNode growth = parse(dinosaur.getGrowthRuleSnapshot());
        long juvenile = positive(growth, "juvenileXp");
        long adult = positive(growth, "adultXp");
        if (adult <= juvenile) throw inconsistent();
        DinosaurStage before = dinosaur.getStage();
        dinosaur.applyCareXp(gained, juvenile, adult);
        budget.award(gained);
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        if (!affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(
                userId, dinosaur.getId(), today, AFFINITY_KIND)) {
            long affinityGain = positive(parse(dinosaur.getAffinityRuleSnapshot()), "gain");
            dinosaur.addAffinity(affinityGain);
            affinities.save(RanchAffinityUnitEntity.builder()
                    .ownerId(owner.getId()).userId(userId).dinosaurId(dinosaur.getId())
                    .earnedOn(today).kind(AFFINITY_KIND).gain(affinityGain)
                    .createdAt(now).build());
        }
        dinosaur.advanceVersion();
        owner.advanceVersion();
        budgets.save(budget);
        dinosaurs.save(dinosaur);
        owners.save(owner);

        UUID commandId = UuidV7.generate();
        ledger.save(RanchPointLedgerEntity.builder()
                .ownerId(owner.getId()).userId(userId).commandId(commandId)
                .entryKind("CARE").deltaPoints(0).balanceAfter(owner.getBalance())
                .deltaXp(gained).dinosaurId(dinosaur.getId())
                .ruleSnapshot(encode(Map.of(
                        "care", parse(budget.getRuleSnapshot()),
                        "growth", growth,
                        "affinity", parse(dinosaur.getAffinityRuleSnapshot()))))
                .occurredAt(now).createdAt(now).build());
        FeedingResult result = new FeedingResult(commandId, dinosaur.getId(), CARE_KIND,
                "0", Long.toString(gained), gained < amount, before,
                dinosaur.getStage(), Long.toString(owner.getBalance()), ruleVersion, now);
        RanchCommandEntity command = RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType(TYPE).bodyHash(hash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build();
        command.setId(commandId);
        commands.saveAndFlush(command);
        return new FeedOutcome(result, true);
    }

    public record FeedOutcome(FeedingResult result, boolean createdNow) { }

    private <T> T decode(String saved, Class<T> type) {
        try {
            return json.readValue(saved, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw inconsistent();
        }
    }

    private long positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToLong() || value.longValue() <= 0) {
            throw inconsistent();
        }
        return value.longValue();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw inconsistent();
        }
        return value.asText();
    }

    private long version(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
    }

    private BusinessException notFound() {
        return new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND);
    }

    private BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }

    private BusinessException inconsistent() {
        return new BusinessException(RanchErrorCode.RANCH_008,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
