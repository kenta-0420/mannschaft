package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.CursorPagedResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.ranch.dto.RanchCareRuleSummary;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsResponse;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationResponse;
import com.mannschaft.app.ranch.dto.RanchPolicySummary;
import com.mannschaft.app.ranch.reward.RanchRewardDeliveryBounds;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 非TX入口。fresh ACTIVE/SYSTEM_ADMINの後、Ranch own独立取引へ順次委譲する。 */
@Service
@RequiredArgsConstructor
public class RanchAdminFacade {
    private final RanchAdminAdmission admission;
    private final RanchOperationalControlsReader controls;
    private final RanchCareRuleAdminReader careRules;
    private final RanchCareRulePublicationWriter careWriter;
    private final Clock clock;
    private final RanchAdminInputParser input;
    private final RanchAdminCommandReplayReader replay;
    private final RanchPolicyPublicationWriter policyWriter;
    private final RanchPolicyAdminReader policies;
    private final RanchOperationalControlsWriter controlsWriter;
    private final RanchPublicationReadiness readiness;
    private final RanchRewardDeliveryBounds bounds;

    public RanchOperationalControlsResponse controls(Long actorId) {
        return admission.checked(actorId, () -> controls.read(now()));
    }

    public CursorPagedResponse<RanchCareRuleSummary> careRules(Long actorId, String cursor, int limit) {
        return admission.checked(actorId, () -> careRules.read(actorId, cursor, limit));
    }

    public RanchCareRulePublicationWriter.PublicationOutcome publishCare(Long actorId, UUID key, JsonNode body) {
        return admission.checked(actorId, () -> careWriter.publish(actorId, key, input.care(body), now()));
    }

    public CursorPagedResponse<RanchPolicySummary> policies(Long actorId, String cursor, int limit) {
        return admission.checked(actorId, () -> policies.read(actorId, cursor, limit));
    }

    public RanchPolicyPublicationWriter.PublicationOutcome publishPolicy(Long actorId, UUID key, JsonNode body) {
        return admission.checked(actorId, () -> {
            var request = input.policy(body);
            var saved = replay.read(actorId, key, RanchPolicyPublicationWriter.KIND,
                    policyWriter.commandHash(request), RanchPolicyPublicationResponse.class);
            if (saved.isPresent()) return new RanchPolicyPublicationWriter.PublicationOutcome(saved.orElseThrow(), false);
            RanchPublicationReadiness.Snapshot snapshot = null;
            if (request.enabled()) {
                snapshot = requireReadiness();
                try { bounds.validate(RanchPolicyPublicationWriter.delivery(request)); }
                catch (IllegalStateException missing) { throw unavailable(); }
                catch (IllegalArgumentException invalid) { throw new BusinessException(RanchErrorCode.RANCH_006); }
            }
            return policyWriter.publish(actorId, key, request, snapshot, now());
        });
    }

    public RanchOperationalControlsResponse updateControls(Long actorId, UUID key, JsonNode body) {
        return admission.checked(actorId, () -> {
            var request = input.controls(body);
            var saved = replay.read(actorId, key, RanchOperationalControlsWriter.KIND,
                    controlsWriter.commandHash(request), RanchOperationalControlsResponse.class);
            if (saved.isPresent()) return saved.orElseThrow();
            var current = controls.read(now());
            boolean opening = request.isCareEnabled() && !current.isCareEnabled()
                    || request.isShopEnabled() && !current.isShopEnabled();
            // 診断domainの承認照会はwriter取引に入る前だけ行う。
            var snapshot = opening ? requireReadiness() : null;
            return controlsWriter.update(actorId, key, request, snapshot, now());
        });
    }

    private RanchPublicationReadiness.Snapshot requireReadiness() {
        var snapshot = readiness.current();
        if (!snapshot.careReady()) throw unavailable();
        return snapshot;
    }

    private static BusinessException unavailable() {
        return new BusinessException(RanchErrorCode.RANCH_004, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }
}
