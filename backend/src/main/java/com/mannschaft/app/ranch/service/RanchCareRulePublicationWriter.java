package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationResponse;
import com.mannschaft.app.ranch.entity.RanchAdminCommandEntity;
import com.mannschaft.app.ranch.entity.RanchCareRuleEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** fresh管理者admissionからだけ呼ぶ。成功ACKとcare公開版を同じRanch PRIMARY TXに保存する。 */
@Service
@RequiredArgsConstructor
public class RanchCareRulePublicationWriter {
    private static final String KIND = "ADMIN_CARE_PUBLISH";
    private static final String RESOURCE = "/api/v1/system-admin/ranch/care-rules";
    private final RanchAdminCommandRepository commands;
    private final RanchCareRuleRepository rules;
    private final RanchOperationalControlRepository controls;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PublicationOutcome publish(Long actorId, UUID key, RanchCareRulePublicationRequest request, Instant serverTime) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        byte[] hash = hasher.hash(KIND, RESOURCE, null, json.valueToTree(request));
        var saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        controls.lockSingleton().orElseThrow(() -> new BusinessException(RanchErrorCode.RANCH_004));
        // 異なる管理主体も単一行で直列化し、同主体の同key競合を再確認する。
        saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        // HTTP以外のtrusted呼出しでも保存値を検証し、保存ACKにはlive検証を再適用しない。
        new RanchAdminInputParser().care(json.valueToTree(request));
        RanchAdminPublicationCalendar.requireFutureWeek(request.effectiveAt(), now);
        if (rules.existsByEffectiveAt(request.effectiveAt())) throw new BusinessException(RanchErrorCode.RANCH_007);
        long version;
        try { version = Math.addExact(rules.findTopByOrderByVersionNumberDesc().map(rule -> rule.getVersionNumber()).orElse(0L), 1); }
        catch (ArithmeticException exception) { throw new BusinessException(RanchErrorCode.RANCH_008, exception); }
        UUID id = UuidV7.generate();
        var rule = RanchCareRuleEntity.builder().id(id).versionNumber(version).effectiveAt(request.effectiveAt())
                .amountXp(Long.parseLong(request.amountXp())).weeklyCapXp(Long.parseLong(request.weeklyCapXp()))
                .juvenileXp(Long.parseLong(request.juvenileXp())).adultXp(Long.parseLong(request.adultXp()))
                .contentHash(hash.clone()).publishedBy(actorId).publishedAt(now).build();
        rules.saveAndFlush(rule);
        var response = new RanchCareRulePublicationResponse(id, Long.toString(version), HexFormat.of().formatHex(hash),
                request.effectiveAt(), request, now, Long.toString(actorId));
        UUID commandId = UuidV7.generate();
        try {
            commands.saveAndFlush(RanchAdminCommandEntity.builder().id(commandId).actorUserId(actorId).idempotencyKey(key)
                    .commandType(KIND).bodyHash(hash.clone()).resultJson(json.writeValueAsString(response)).completedAt(now).build());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_009, exception);
        }
        events.publishEvent(new RanchAdminActionRecorded(actorId, commandId, KIND, id.toString(), request.reasonCode(), now));
        return new PublicationOutcome(response, true);
    }

    private PublicationOutcome replay(RanchAdminCommandEntity command, byte[] hash) {
        if (!KIND.equals(command.getCommandType()) || !Arrays.equals(hash, command.getBodyHash())) {
            throw new BusinessException(RanchErrorCode.RANCH_003);
        }
        try { return new PublicationOutcome(json.readValue(command.getResultJson(), RanchCareRulePublicationResponse.class), false); }
        catch (JsonProcessingException exception) { throw new BusinessException(RanchErrorCode.RANCH_008, exception); }
    }

    public record PublicationOutcome(RanchCareRulePublicationResponse response, boolean createdNow) { }
}
