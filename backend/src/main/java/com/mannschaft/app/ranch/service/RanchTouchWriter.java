package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.InteractionKind;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.InteractionResult;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.entity.RanchAffinityUnitEntity;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchAffinityUnitRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
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
import java.util.Objects;
import java.util.UUID;

/** 卵やcare OFF時もTOUCHへ短く応答し、対象UTC日の初回だけ親密度を加算する。 */
@Service
@RequiredArgsConstructor
public class RanchTouchWriter {
    private static final String TYPE = "TOUCH";
    private static final String RESOURCE = "/api/v1/me/ranch/interactions";

    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchAffinityUnitRepository affinities;
    private final RanchCommandRepository commands;
    private final RanchRuleProvider rules;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InteractionResult touch(Long userId, UUID key, RanchInteractionRequest request,
                                   Instant serverTime) {
        return touchOutcome(userId, key, request, serverTime).result();
    }

    /** 保存済みの本文は維持し、HTTPの初回201/再送200だけを区別する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TouchOutcome touchOutcome(Long userId, UUID key, RanchInteractionRequest request,
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
            return new TouchOutcome(decode(command.getResultJson()), false);
        }
        if (request.kind() != InteractionKind.TOUCH) {
            throw new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
        }
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
        var afterLock = commands.lockByUserIdAndIdempotencyKey(userId, key);
        if (afterLock.isPresent()) {
            RanchCommandEntity command = afterLock.orElseThrow();
            if (!TYPE.equals(command.getCommandType())
                    || !Arrays.equals(hash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            return new TouchOutcome(decode(command.getResultJson()), false);
        }
        if (owner.getVersion() != version(request.version())) {
            throw new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
        }
        RanchDinosaurEntity dinosaur = dinosaurs.findByUserId(userId)
                .orElseThrow(this::inconsistent);
        if (!owner.getId().equals(dinosaur.getOwnerId())) throw inconsistent();
        JsonNode frozen = parse(dinosaur.getAffinityRuleSnapshot());
        long gain = positive(frozen, "gain");
        long warm = positive(frozen, "warmAffinity");
        long close = positive(frozen, "closeAffinity");
        if (close <= warm) throw inconsistent();
        boolean affinityChanged = false;
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        if (owner.getStatus() == ParticipationStatus.ACTIVE && rules.careEnabled()
                && !affinities.existsByUserIdAndDinosaurIdAndEarnedOnAndKind(
                        userId, dinosaur.getId(), today, TYPE)) {
            dinosaur.addAffinity(gain);
            dinosaur.advanceVersion();
            affinities.save(RanchAffinityUnitEntity.builder()
                    .ownerId(owner.getId()).userId(userId).dinosaurId(dinosaur.getId())
                    .earnedOn(today).kind(TYPE).gain(gain).createdAt(now).build());
            dinosaurs.save(dinosaur);
            affinityChanged = true;
        }
        owner.advanceVersion();
        owners.save(owner);
        String band = dinosaur.getAffinity() >= close ? "CLOSE"
                : dinosaur.getAffinity() >= warm ? "WARM" : "NEUTRAL";
        String reaction = dinosaur.getStage() == DinosaurStage.EGG
                ? "EGG_TOUCH" : "DINOSAUR_TOUCH";
        UUID commandId = UuidV7.generate();
        InteractionResult result = new InteractionResult(commandId, dinosaur.getId(),
                reaction, band, affinityChanged, now);
        RanchCommandEntity command = RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType(TYPE).bodyHash(hash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build();
        command.setId(commandId);
        commands.saveAndFlush(command);
        return new TouchOutcome(result, true);
    }

    public record TouchOutcome(InteractionResult result, boolean createdNow) { }

    private InteractionResult decode(String saved) {
        try {
            return json.readValue(saved, InteractionResult.class);
        } catch (JsonProcessingException exception) {
            throw inconsistent();
        }
    }

    private String encode(Object result) {
        try {
            return json.writeValueAsString(result);
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

    private BusinessException inconsistent() {
        return new BusinessException(RanchErrorCode.RANCH_008,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
