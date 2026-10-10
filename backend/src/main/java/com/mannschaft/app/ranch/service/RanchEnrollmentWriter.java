package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.MotionMode;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.RenderStyle;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchParticipationPeriodEntity;
import com.mannschaft.app.ranch.entity.RanchRoomPlacementEntity;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Auth所有のACTIVE行ロック下からだけ呼ぶ、牧場参加の独立TX writer。 */
@Service
@RequiredArgsConstructor
public class RanchEnrollmentWriter {
    private static final String COMMAND_TYPE = "ENROLL";
    private static final String RESOURCE = "/api/v1/me/ranch";
    private static final String[] SLOT_KEYS = {"SHELF_1", "SHELF_2", "SHELF_3"};

    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchRoomPlacementRepository slots;
    private final RanchParticipationPeriodRepository periods;
    private final RanchCareWeekBudgetRepository careBudgets;
    private final RanchCommandRepository commands;
    private final RanchRuleProvider rules;
    private final RanchStateAssembler states;
    private final ObjectMapper json;

    /** 成功replayをlive rule検証より先にPRIMARYで検索し、全行を同TXで保存する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EnrollmentOutcome enroll(Long userId, UUID idempotencyKey, Instant serverTime,
                                    RanchStateAssembler.ExternalProjection external) {
        Objects.requireNonNull(userId, "本人IDは必須です");
        Objects.requireNonNull(idempotencyKey, "操作キーは必須です");
        Instant now = Objects.requireNonNull(serverTime, "時刻は必須です")
                .truncatedTo(ChronoUnit.MICROS);
        byte[] bodyHash = new RanchCommandHasher()
                .hash(COMMAND_TYPE, RESOURCE, null, json.createObjectNode());

        var previous = commands.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (previous.isPresent()) {
            RanchCommandEntity command = previous.orElseThrow();
            if (!COMMAND_TYPE.equals(command.getCommandType())
                    || !Arrays.equals(bodyHash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            RanchState oldState = decode(command.getResultJson());
            return new EnrollmentOutcome(false, oldState.owner().id(),
                    oldState.dinosaur().id(), command.getId(), oldState);
        }

        var existing = owners.findByUserId(userId);
        if (existing.isPresent()) {
            RanchOwnerEntity owner = existing.orElseThrow();
            RanchDinosaurEntity dinosaur = dinosaurs.findByUserId(userId)
                    .orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_008,
                            HttpStatus.INTERNAL_SERVER_ERROR));
            return saveCommand(userId, idempotencyKey, owner.getId(), dinosaur.getId(),
                    bodyHash, now, external, false);
        }

        RanchRuleProvider.EggRuleSnapshot rule = rules.currentEggRule(now)
                .orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_004,
                        HttpStatus.SERVICE_UNAVAILABLE));
        if (rule.durationSeconds() <= rule.wideCrackSeconds()
                || rule.wideCrackSeconds() <= rule.smallCrackSeconds()
                || rule.smallCrackSeconds() <= 0
                || rule.juvenileXp() <= 0 || rule.adultXp() <= rule.juvenileXp()) {
            throw new BusinessException(RanchErrorCode.RANCH_008,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        RanchOwnerEntity owner = owners.saveAndFlush(RanchOwnerEntity.builder()
                .userId(userId).status(ParticipationStatus.ACTIVE).balance(0)
                .viewMode("ROOM").renderStyle(RenderStyle.PIXEL)
                .motionMode(MotionMode.REDUCED).soundEnabled(false).soundVolume(50)
                .version(0).createdAt(now).build());
        RanchDinosaurEntity dinosaur = dinosaurs.saveAndFlush(RanchDinosaurEntity.builder()
                .ownerId(owner.getId()).userId(userId)
                .eggStartedAt(now).eggReadyAt(now.plusSeconds(rule.durationSeconds()))
                .eggRuleSnapshot(encode(Map.of(
                        "ruleVersion", rule.ruleVersion(),
                        "durationSeconds", rule.durationSeconds(),
                        "smallCrackSeconds", rule.smallCrackSeconds(),
                        "wideCrackSeconds", rule.wideCrackSeconds())))
                .growthRuleSnapshot(encode(Map.of(
                        "ruleVersion", rule.ruleVersion(),
                        "juvenileXp", rule.juvenileXp(), "adultXp", rule.adultXp())))
                .affinityRuleSnapshot(encode(Map.of(
                        "ruleVersion", rule.ruleVersion(), "gain", rule.affinityGain(),
                        "warmAffinity", rule.warmAffinity(), "closeAffinity", rule.closeAffinity())))
                .stage(DinosaurStage.EGG).xp(0).affinity(0).version(0)
                .createdAt(now).build());
        periods.save(RanchParticipationPeriodEntity.builder()
                .ownerId(owner.getId()).userId(userId).startsAt(now).createdAt(now).build());
        for (String slotKey : SLOT_KEYS) {
            slots.save(RanchRoomPlacementEntity.builder()
                    .ownerId(owner.getId()).userId(userId).slotKey(slotKey)
                    .version(0).createdAt(now).build());
        }
        return saveCommand(userId, idempotencyKey, owner.getId(), dinosaur.getId(),
                bodyHash, now, external, true);
    }

    private EnrollmentOutcome saveCommand(Long userId, UUID key, UUID ownerId,
                                          UUID dinosaurId, byte[] bodyHash,
                                          Instant now,
                                          RanchStateAssembler.ExternalProjection external,
                                          boolean createdNow) {
        Objects.requireNonNull(external, "認可済み外domain投影は必須です");
        LocalDate monday = now.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        RanchState state = states.assemble(userId, now, external,
                owners.findById(ownerId).orElseThrow(),
                dinosaurs.findById(dinosaurId).orElseThrow(),
                slots.findByUserIdOrderBySlotKey(userId),
                careBudgets.findByUserIdAndWeekStartsOn(userId, monday).orElse(null),
                rules.currentCareRule(now));
        String resultJson = encode(state);
        RanchCommandEntity command = commands.saveAndFlush(RanchCommandEntity.builder()
                .ownerId(ownerId).userId(userId).idempotencyKey(key)
                .commandType(COMMAND_TYPE).bodyHash(bodyHash).resultJson(resultJson)
                .completedAt(now).createdAt(now).build());
        return new EnrollmentOutcome(createdNow, ownerId, dinosaurId,
                command.getId(), state);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private RanchState decode(String value) {
        try {
            return json.readValue(value, RanchState.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    public record EnrollmentOutcome(boolean createdNow, UUID ownerId, UUID dinosaurId,
                                    UUID commandId, RanchState snapshot) { }
}
