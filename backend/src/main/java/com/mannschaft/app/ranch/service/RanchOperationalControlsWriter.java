package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsRequest;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsResponse;
import com.mannschaft.app.ranch.entity.RanchAdminCommandEntity;
import com.mannschaft.app.ranch.entity.RanchRewardPausePeriodEntity;
import com.mannschaft.app.ranch.repository.RanchAdminCommandRepository;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchRewardPausePeriodRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 制御行・停止期間・固定ACKを同じ取引へ保存する。既得報酬を遡及変更しない。 */
@Service
@RequiredArgsConstructor
public class RanchOperationalControlsWriter {
    static final String KIND = "ADMIN_CONTROLS_UPDATE";
    static final String RESOURCE = "/api/v1/system-admin/ranch/operational-controls";
    private final RanchOperationalControlRepository controls;
    private final RanchRewardPausePeriodRepository pauses;
    private final RanchCareRuleRepository careRules;
    private final RanchShopCatalogRepository shop;
    private final RanchAdminCommandRepository commands;
    private final RanchProductionMasterRegistry master;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    byte[] commandHash(RanchOperationalControlsRequest request) {
        return hasher.hash(KIND, RESOURCE, null, json.valueToTree(request));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public RanchOperationalControlsResponse update(Long actorId, UUID key,
            RanchOperationalControlsRequest request, RanchPublicationReadiness.Snapshot readiness,
            Instant serverTime) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(key);
        Objects.requireNonNull(request);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        byte[] hash = commandHash(request);
        var saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        var control = controls.lockSingleton().orElseThrow(RanchOperationalControlsWriter::unavailable);
        saved = commands.findByActorUserIdAndIdempotencyKey(actorId, key);
        if (saved.isPresent()) return replay(saved.orElseThrow(), hash);
        new RanchAdminInputParser().controls(json.valueToTree(request));
        if (control.getVersion() != Long.parseLong(request.version())) throw conflict();
        boolean openingCare = request.isCareEnabled() && !control.isCareEnabled();
        boolean openingShop = request.isShopEnabled() && !control.isShopEnabled();
        // 保存前だけの503。停止・配送保留の操作を未登録masterで阻害しない。
        if (openingCare || openingShop) {
            if (readiness == null || !readiness.careReady()) throw unavailable();
            try { master.requireCurrentVersion(readiness.masterVersion()); }
            catch (IllegalStateException unavailableMaster) { throw unavailable(); }
            if (openingCare && careRules.publishedAt(now, PageRequest.of(0, 1)).isEmpty()) throw unavailable();
            if (openingShop && !shop.hasApprovedItems()) throw unavailable();
        }
        var open = pauses.openPeriods(PageRequest.of(0, 2));
        if (open.size() > 1) throw new BusinessException(RanchErrorCode.RANCH_008);
        if (!request.isRewardsPaused() && !open.isEmpty() && !now.isAfter(open.get(0).getStartsAt())) throw conflict();
        // ここから保存するため、以後RANCH_004を返す分岐は置かない。
        if (request.isRewardsPaused() && open.isEmpty()) {
            pauses.saveAndFlush(RanchRewardPausePeriodEntity.builder().id(UuidV7.generate())
                    .startsAt(now).reasonCode(request.reasonCode()).changedBy(actorId).build());
        } else if (!request.isRewardsPaused() && !open.isEmpty()) {
            open.get(0).closeAt(now);
            pauses.saveAndFlush(open.get(0));
        }
        try { control.apply(request.isCareEnabled(), request.isShopEnabled(), request.isDeliveryPaused(), actorId, now); }
        catch (ArithmeticException overflow) { throw new BusinessException(RanchErrorCode.RANCH_008, overflow); }
        controls.saveAndFlush(control);
        var response = new RanchOperationalControlsResponse(Long.toString(control.getVersion()), control.isCareEnabled(),
                control.isShopEnabled(), control.isDeliveryPaused(), request.isRewardsPaused(), now);
        UUID commandId = UuidV7.generate();
        try {
            commands.saveAndFlush(RanchAdminCommandEntity.builder().id(commandId).actorUserId(actorId)
                    .idempotencyKey(key).commandType(KIND).bodyHash(hash.clone())
                    .resultJson(json.writeValueAsString(response)).completedAt(now).build());
        } catch (JsonProcessingException failure) { throw new BusinessException(RanchErrorCode.RANCH_009, failure); }
        events.publishEvent(new RanchAdminActionRecorded(actorId, commandId, KIND, "1", request.reasonCode(), now));
        return response;
    }

    private RanchOperationalControlsResponse replay(RanchAdminCommandEntity command, byte[] hash) {
        return RanchAdminCommandReplayReader.decode(command, KIND, hash, RanchOperationalControlsResponse.class, json);
    }
    private static BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }
    private static BusinessException conflict() { return new BusinessException(RanchErrorCode.RANCH_007, HttpStatus.CONFLICT); }
}
