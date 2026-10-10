package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.ParticipationStatus;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.OwnerSummary;
import com.mannschaft.app.ranch.dto.RanchSettings;
import com.mannschaft.app.ranch.dto.RanchSettingsRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.entity.RanchParticipationPeriodEntity;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchParticipationPeriodRepository;
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

/** 認証側ACTIVE本人ロックの内側で、独立した牧場取引を実行する。 */
@Service
@RequiredArgsConstructor
public class RanchOwnerCommandWriter {
    private static final String SETTINGS_PATH = "/api/v1/me/ranch/settings";
    private static final String PAUSE_PATH = "/api/v1/me/ranch/pause";
    private static final String RESUME_PATH = "/api/v1/me/ranch/resume";

    private final RanchOwnerRepository owners;
    private final RanchParticipationPeriodRepository periods;
    private final RanchCommandRepository commands;
    private final ObjectMapper json;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RanchSettings settings(Long userId, UUID key, RanchSettingsRequest request,
                                  boolean canonicalVisibility, Instant serverTime) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        byte[] hash = hasher.hash("SETTINGS", SETTINGS_PATH, null, json.valueToTree(request));
        RanchSettings replay = replay(userId, key, "SETTINGS", hash, RanchSettings.class);
        if (replay != null) return replay;
        if (request.renderStyle() == null || request.motionMode() == null
                || request.isSoundEnabled() == null || request.soundVolume() == null
                || request.soundVolume() < 0 || request.soundVolume() > 100) {
            throw badInput();
        }
        RanchOwnerEntity owner = owner(userId);
        verifyVersion(owner, version(request.version()));
        owner.updateSettings(request.renderStyle(), request.motionMode(),
                request.isSoundEnabled(), request.soundVolume());
        owners.saveAndFlush(owner);
        RanchSettings result = new RanchSettings(canonicalVisibility, owner.getViewMode(),
                owner.getRenderStyle(), owner.getMotionMode(), owner.isSoundEnabled(),
                owner.getSoundVolume(), Long.toString(owner.getVersion()));
        save(owner, userId, key, "SETTINGS", hash, result, serverTime);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OwnerSummary pause(Long userId, UUID key, RanchVersionRequest request,
                              Instant serverTime) {
        return participation(userId, key, request, serverTime, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OwnerSummary resume(Long userId, UUID key, RanchVersionRequest request,
                               Instant serverTime) {
        return participation(userId, key, request, serverTime, false);
    }

    private OwnerSummary participation(Long userId, UUID key, RanchVersionRequest request,
                                       Instant serverTime, boolean pause) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        String type = pause ? "PAUSE" : "RESUME";
        byte[] hash = hasher.hash(type, pause ? PAUSE_PATH : RESUME_PATH,
                null, json.valueToTree(request));
        OwnerSummary replay = replay(userId, key, type, hash, OwnerSummary.class);
        if (replay != null) return replay;
        long expectedVersion = version(request.version());
        Instant now = time(serverTime);
        RanchOwnerEntity owner = owner(userId);
        verifyVersion(owner, expectedVersion);
        if (pause) {
            if (owner.getStatus() != ParticipationStatus.ACTIVE) throw conflict();
            owner.pause();
            var active = periods.findByUserIdAndEndsAtIsNull(userId);
            if (active.size() != 1 || !owner.getId().equals(active.get(0).getOwnerId())) {
                throw inconsistent();
            }
            RanchParticipationPeriodEntity period = active.get(0);
            if (!now.isAfter(period.getStartsAt())) throw conflict();
            period.closeAt(now);
            periods.save(period);
        } else {
            if (owner.getStatus() != ParticipationStatus.PAUSED) throw conflict();
            owner.resume();
            if (!periods.findByUserIdAndEndsAtIsNull(userId).isEmpty()) {
                throw inconsistent();
            }
            periods.save(RanchParticipationPeriodEntity.builder()
                    .ownerId(owner.getId()).userId(userId)
                    .startsAt(now).createdAt(now).build());
        }
        owners.saveAndFlush(owner);
        OwnerSummary result = new OwnerSummary(owner.getId(), owner.getStatus(),
                Long.toString(owner.getBalance()), Long.toString(owner.getVersion()));
        save(owner, userId, key, type, hash, result, now);
        return result;
    }

    private RanchOwnerEntity owner(Long userId) {
        return owners.lockByUserId(userId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
    }

    private <T> T replay(Long userId, UUID key, String type, byte[] hash,
                         Class<T> resultClass) {
        var previous = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (previous.isEmpty()) return null;
        RanchCommandEntity command = previous.orElseThrow();
        if (!type.equals(command.getCommandType())
                || !Arrays.equals(hash, command.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
        }
        try {
            return json.readValue(command.getResultJson(), resultClass);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_008, exception);
        }
    }

    private void save(RanchOwnerEntity owner, Long userId, UUID key, String type,
                      byte[] hash, Object result, Instant serverTime) {
        try {
            Instant now = time(serverTime);
            commands.saveAndFlush(RanchCommandEntity.builder()
                    .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                    .commandType(type).bodyHash(hash)
                    .resultJson(json.writeValueAsString(result))
                    .completedAt(now).createdAt(now).build());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
    }

    private long version(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) throw badInput();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw badInput();
        }
    }

    private void verifyVersion(RanchOwnerEntity owner, long expected) {
        if (owner.getVersion() != expected) throw conflict();
    }

    private BusinessException conflict() {
        return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
    }

    private Instant time(Instant value) {
        return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS);
    }

    private BusinessException badInput() {
        return new BusinessException(RanchErrorCode.RANCH_006, HttpStatus.BAD_REQUEST);
    }

    private BusinessException inconsistent() {
        return new BusinessException(RanchErrorCode.RANCH_008,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
