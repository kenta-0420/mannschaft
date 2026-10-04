package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationResponse;
import com.mannschaft.app.ranch.entity.RanchAdminCommandEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPolicyEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPolicyRepository;
import com.mannschaft.app.ranch.reward.RanchRewardDeliveryBounds;
import com.mannschaft.app.ranch.reward.RanchRewardPolicyCodec;
import com.mannschaft.app.ranch.reward.RanchRewardPolicySnapshot;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 公開版と保存ACKを単一PRIMARY取引で保存する。既存の週snapshotを変更しない。 */
@Service
@RequiredArgsConstructor
public class RanchPolicyPublicationWriter {
    static final String KIND = "ADMIN_POLICY_PUBLISH";
    static final String RESOURCE = "/api/v1/system-admin/ranch/policies";
    private final RanchAdminCommandRepository commands;
    private final RanchRewardPolicyRepository policies;
    private final RanchOperationalControlRepository controls;
    private final RanchProductionMasterRegistry master;
    private final RanchRewardDeliveryBounds bounds;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    byte[] commandHash(RanchPolicyPublicationRequest request) {
        return hasher.hash(KIND, RESOURCE, null, json.valueToTree(request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public PublicationOutcome publish(Long actorId, UUID key, RanchPolicyPublicationRequest request,
            RanchPublicationReadiness.Snapshot readiness, Instant serverTime) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        byte[] hash = commandHash(request);
        var saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        controls.lockSingleton().orElseThrow(RanchPolicyPublicationWriter::unavailable);
        saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        new RanchAdminInputParser().policy(json.valueToTree(request));
        RanchAdminPublicationCalendar.requireFutureWeek(request.effectiveAt(), now);
        var delivery = delivery(request);
        // 503はすべて保存前の確定拒否。非TXで取得した承認版をown TXで再照合する。
        if (request.enabled()) {
            if (readiness == null || !readiness.careReady()) throw unavailable();
            try { master.requireCurrentVersion(readiness.masterVersion()); bounds.validate(delivery); }
            catch (IllegalStateException missing) { throw unavailable(); }
            catch (IllegalArgumentException outOfBounds) { throw new BusinessException(RanchErrorCode.RANCH_006); }
        }
        if (policies.existsByEffectiveAt(request.effectiveAt())) {
            throw new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT);
        }
        long version;
        try { version = Math.addExact(policies.findTopByOrderByVersionNumberDesc()
                .map(RanchRewardPolicyEntity::getVersionNumber).orElse(0L), 1); }
        catch (ArithmeticException overflow) { throw new BusinessException(RanchErrorCode.RANCH_008, overflow); }
        UUID id = UuidV7.generate();
        var sources = new EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule>(RanchRewardSourceType.class);
        request.sources().forEach(rule -> sources.put(rule.sourceType(), new RanchRewardPolicySnapshot.SourceRule(
                rule.enabled(), Long.parseLong(rule.amountPoints()), rule.countLimit())));
        var snapshot = new RanchRewardPolicySnapshot(id, version, request.effectiveAt(), request.enabled(),
                Long.parseLong(request.globalWeeklyCap()), sources, delivery, request.reasonCode());
        var encoded = RanchRewardPolicyCodec.encode(snapshot, json);
        policies.saveAndFlush(RanchRewardPolicyEntity.builder().id(id).versionNumber(version)
                .effectiveAt(request.effectiveAt()).schemaVersion(1).settingsJson(encoded.json())
                .contentHash(encoded.sha256()).publishedBy(actorId).publishedAt(now).build());
        var response = new RanchPolicyPublicationResponse(id, Long.toString(version),
                HexFormat.of().formatHex(encoded.sha256()), request.effectiveAt(), request, now, Long.toString(actorId));
        UUID commandId = UuidV7.generate();
        try {
            commands.saveAndFlush(RanchAdminCommandEntity.builder().id(commandId).actorUserId(actorId)
                    .idempotencyKey(key).commandType(KIND).bodyHash(hash.clone())
                    .resultJson(json.writeValueAsString(response)).completedAt(now).build());
        } catch (JsonProcessingException failure) { throw new BusinessException(RanchErrorCode.RANCH_009, failure); }
        events.publishEvent(new RanchAdminActionRecorded(actorId, commandId, KIND, id.toString(), request.reasonCode(), now));
        return new PublicationOutcome(response, true);
    }

    static RanchRewardPolicySnapshot.DeliverySettings delivery(RanchPolicyPublicationRequest request) {
        var value = request.delivery();
        return new RanchRewardPolicySnapshot.DeliverySettings(value.batchSize(), value.leaseSeconds(),
                value.maxAttempts(), value.initialBackoffSeconds(), value.maxBackoffSeconds());
    }

    private PublicationOutcome replay(RanchAdminCommandEntity command, byte[] hash) {
        return new PublicationOutcome(RanchAdminCommandReplayReader.decode(command, KIND, hash,
                RanchPolicyPublicationResponse.class, json), false);
    }
    private static BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }
    public record PublicationOutcome(RanchPolicyPublicationResponse response, boolean createdNow) { }
}
