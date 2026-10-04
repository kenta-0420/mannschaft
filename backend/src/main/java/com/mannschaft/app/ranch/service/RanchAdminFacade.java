package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.CursorPagedResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.ranch.dto.RanchCareRuleSummary;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsResponse;
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

    public RanchOperationalControlsResponse controls(Long actorId) {
        return admission.checked(actorId, () -> controls.read(now()));
    }

    public CursorPagedResponse<RanchCareRuleSummary> careRules(Long actorId, String cursor, int limit) {
        return admission.checked(actorId, () -> careRules.read(actorId, cursor, limit));
    }

    public RanchCareRulePublicationWriter.PublicationOutcome publishCare(Long actorId, UUID key, JsonNode body) {
        return admission.checked(actorId, () -> careWriter.publish(actorId, key, input.care(body), now()));
    }

    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }
}