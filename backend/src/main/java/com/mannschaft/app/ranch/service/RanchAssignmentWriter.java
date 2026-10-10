package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.AssignmentMethod;
import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.AssignmentResult;
import com.mannschaft.app.ranch.dto.RanchAssignmentRequest;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchDinosaurEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 保存済み再送を、生きた診断・出生結果の照合より先に確認する。 */
@Service
@RequiredArgsConstructor
public class RanchAssignmentWriter {
    private static final String TYPE = "ASSIGNMENT";
    private static final String RESOURCE = "/api/v1/me/ranch/assignment";

    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    /** 認証通過後、外domainの現行根拠を読む前に順次呼び出す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public Optional<AssignmentResult> savedReplay(Long userId, UUID key,
                                                  RanchAssignmentRequest request) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        return lookup(userId, key, hash(request));
    }

    /** 本人の完了済み根拠をsource facadeが検証した選定のみ受け取る。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AssignmentResult assign(Long userId, UUID key, RanchAssignmentRequest request,
                                   RanchAssignmentResolver.Selection selection,
                                   Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        byte[] bodyHash = hash(request);
        var previous = lookup(userId, key, bodyHash);
        if (previous.isPresent()) return previous.orElseThrow();

        validateShape(request);
        validateTrustedSelection(request, selection);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        RanchOwnerEntity owner = owners.lockByUserId(userId).orElseThrow(this::notFound);
        // 初回 lookup から owner lock 待ちの間に同keyが成功した場合も元ACKを優先する。
        var committedDuringWait = lookupAfterOwnerLock(userId, key, bodyHash);
        if (committedDuringWait.isPresent()) return committedDuringWait.orElseThrow();
        if (owner.getStatus() != ParticipationStatus.ACTIVE
                || owner.getVersion() != version(request.version())) {
            throw conflict();
        }
        RanchDinosaurEntity dinosaur = dinosaurs.findByUserId(userId)
                .orElseThrow(this::inconsistent);
        if (!owner.getId().equals(dinosaur.getOwnerId())) throw inconsistent();
        if (dinosaur.getStage() != DinosaurStage.EGG
                || dinosaur.getSelectionConfirmedAt() != null) {
            throw conflict();
        }
        dinosaur.confirmAssignment(selection.method(), selection.habitat(),
                selection.speciesKey(), selection.variantKey(),
                selection.catalogVersion(), selection.ruleVersion(),
                selection.resultId(), selection.inputHash(), now);
        owner.advanceVersion();
        dinosaurs.save(dinosaur);
        owners.save(owner);
        AssignmentResult result = new AssignmentResult(dinosaur.getId(),
                selection.method(), selection.speciesKey(), selection.variantKey(),
                selection.habitat(), Long.toString(selection.catalogVersion()),
                now, Long.toString(owner.getVersion()));
        commands.saveAndFlush(RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType(TYPE).bodyHash(bodyHash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build());
        return result;
    }

    private byte[] hash(RanchAssignmentRequest request) {
        return hasher.hash(TYPE, RESOURCE, null, json.valueToTree(request));
    }

    private Optional<AssignmentResult> lookup(Long userId, UUID key, byte[] hash) {
        return decode(commands.findByUserIdAndIdempotencyKey(userId, key), hash);
    }

    private Optional<AssignmentResult> lookupAfterOwnerLock(Long userId, UUID key, byte[] hash) {
        // InnoDB REPEATABLE READ の初回snapshotを再利用せず、現在のcommitを確認する。
        return decode(commands.lockByUserIdAndIdempotencyKey(userId, key), hash);
    }

    private Optional<AssignmentResult> decode(Optional<RanchCommandEntity> previous, byte[] hash) {
        if (previous.isEmpty()) return Optional.empty();
        RanchCommandEntity command = previous.orElseThrow();
        if (!TYPE.equals(command.getCommandType())
                || !Arrays.equals(hash, command.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            return Optional.of(json.readValue(command.getResultJson(), AssignmentResult.class));
        } catch (JsonProcessingException exception) {
            throw inconsistent();
        }
    }

    private void validateShape(RanchAssignmentRequest request) {
        if (request.method() == null) throw badInput();
        switch (request.method()) {
            case HABITAT_RANDOM -> {
                if (request.habitat() == null || request.resultId() != null
                        || request.confirmationRef() != null) throw badInput();
            }
            case DIAGNOSIS -> {
                if (request.resultId() == null || request.habitat() != null
                        || request.confirmationRef() != null) throw badInput();
            }
            case BIRTH_STYLE -> {
                if (request.resultId() == null || request.habitat() != null
                        || request.confirmationRef() == null
                        || request.confirmationRef().isBlank()) throw badInput();
            }
        }
    }

    private void validateTrustedSelection(RanchAssignmentRequest request,
                                          RanchAssignmentResolver.Selection selection) {
        if (selection == null || selection.method() != request.method()
                || selection.habitat() == null
                || (request.method() == AssignmentMethod.HABITAT_RANDOM
                        && selection.habitat() != request.habitat())
                || (request.method() != AssignmentMethod.HABITAT_RANDOM
                        && !request.resultId().equals(selection.resultId()))
                || selection.speciesKey() == null || selection.speciesKey().isBlank()
                || selection.speciesKey().length() > 60
                || selection.variantKey() == null || selection.variantKey().isBlank()
                || selection.variantKey().length() > 32
                || selection.catalogVersion() <= 0
                || selection.ruleVersion() == null || selection.ruleVersion().isBlank()
                || selection.ruleVersion().length() > 80
                || selection.inputHash() == null
                || !selection.inputHash().matches("[0-9a-f]{64}")) {
            throw inconsistent();
        }
    }

    private String encode(AssignmentResult result) {
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
