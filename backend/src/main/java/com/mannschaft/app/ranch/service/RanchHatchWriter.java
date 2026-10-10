package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.HatchResult;
import com.mannschaft.app.ranch.dto.RanchHatchRequest;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** 警告確認付きの明示孵化と永久名。GETからは呼び出さない。 */
@Service
@RequiredArgsConstructor
public class RanchHatchWriter {
    private static final String TYPE = "HATCH";
    private static final String RESOURCE = "/api/v1/me/ranch/hatch";

    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchRoomPlacementRepository slots;
    private final RanchCareWeekBudgetRepository budgets;
    private final RanchCommandRepository commands;
    private final RanchRuleProvider rules;
    private final RanchStateAssembler states;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();
    private final DinosaurNameValidator names = new DinosaurNameValidator();
    private final RanchCareCalculator weeks = new RanchCareCalculator();

    /** 現在の外部投影を読む前に、保存されたACKをPRIMARYの短い独立TXで確定する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public java.util.Optional<HatchResponse> savedReplay(Long userId, UUID key, RanchHatchRequest request) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        byte[] hash = hasher.hash(TYPE, RESOURCE, null, json.valueToTree(request));
        return commands.findByUserIdAndIdempotencyKey(userId, key).map(command -> {
            if (!TYPE.equals(command.getCommandType()) || !Arrays.equals(hash, command.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            return decode(command.getResultJson());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HatchResponse hatch(Long userId, UUID key, RanchHatchRequest request,
                               Instant serverTime,
                               RanchStateAssembler.ExternalProjection external) {
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
            return decode(command.getResultJson());
        }

        if (!Boolean.TRUE.equals(request.nameConfirmed())) throw badInput();
        String permanentName;
        try {
            permanentName = names.normalize(request.name());
        } catch (IllegalArgumentException exception) {
            throw badInput();
        }
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElseThrow(this::notFound);
        if (owner.getStatus() != ParticipationStatus.ACTIVE) throw conflict();
        RanchDinosaurEntity dinosaur = dinosaurs.findByUserId(userId)
                .orElseThrow(this::inconsistent);
        if (!owner.getId().equals(dinosaur.getOwnerId())) throw inconsistent();
        if (dinosaur.getStage() != DinosaurStage.EGG) {
            if (!permanentName.equals(dinosaur.getName())) throw conflict();
            // 新キーかつ同名なら、その時点の状態を保存済み成功結果として返す。
            RanchState state = states.assemble(userId, now,
                    Objects.requireNonNull(external, "認可済み投影は必須です"), owner,
                    dinosaur, slots.findByUserIdOrderBySlotKey(userId),
                    budgets.findByUserIdAndWeekStartsOn(userId,
                            weeks.weekStartsOn(now)).orElse(null),
                    rules.currentCareRule(now));
            HatchResponse result = new HatchResponse(HatchResponse.Kind.CURRENT_STATE,
                    null, state);
            save(owner, userId, key, hash, result, now);
            return result;
        }

        if (owner.getVersion() != version(request.version())) throw conflict();
        if (dinosaur.getSelectionConfirmedAt() == null
                || dinosaur.getAssignmentMethod() == null
                || dinosaur.getHabitat() == null
                || dinosaur.getSpeciesKey() == null
                || dinosaur.getVariantKey() == null
                || dinosaur.getSpeciesCatalogVersion() == null
                || now.isBefore(dinosaur.getEggReadyAt())) {
            throw conflict();
        }
        dinosaur.hatch(permanentName, now);
        dinosaur.advanceVersion();
        owner.advanceVersion();
        dinosaurs.save(dinosaur);
        owners.save(owner);
        HatchResult hatch = new HatchResult(UuidV7.generate(), dinosaur.getId(), DinosaurStage.BABY,
                permanentName, now, now, Long.toString(owner.getVersion()));
        HatchResponse result = new HatchResponse(HatchResponse.Kind.HATCH_RESULT,
                hatch, null);
        save(owner, userId, key, hash, result, now);
        return result;
    }

    private void save(RanchOwnerEntity owner, Long userId, UUID key, byte[] hash,
                      HatchResponse result, Instant now) {
        var command = RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType(TYPE).bodyHash(hash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build();
        command.setId(result.result() == null ? UuidV7.generate() : result.result().commandId());
        commands.saveAndFlush(command);
    }

    private HatchResponse decode(String saved) {
        try {
            return json.readValue(saved, HatchResponse.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private String encode(Object result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private long version(String raw) {
        if (raw == null || !raw.matches("0|[1-9][0-9]*")) throw badInput();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw badInput();
        }
    }

    private BusinessException badInput() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound() {
        return new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }

    private BusinessException inconsistent() {
        return new BusinessException(RanchErrorCode.RANCH_008,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
