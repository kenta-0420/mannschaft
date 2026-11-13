package com.mannschaft.app.ranch.dto;

import com.mannschaft.app.ranch.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/** 03 API正本のRanchState。BIGINTはdecimal string、日時はUTC瞬間。 */
public record RanchState(String featureStatus, boolean deliveryPaused, String rewardsStatus, boolean shopAvailable,
        @Schema(nullable = true) CareBudget careBudget,
        @Schema(nullable = true) OwnerSummary owner,
        @Schema(nullable = true) DinosaurSummary dinosaur,
        @Schema(nullable = true) RanchSettings settings,
        @Schema(nullable = true) WeekBudget weekBudget,
        List<RoomSlotSummary> roomSlots,
        @Schema(nullable = true) String policyVersion,
        Instant serverTime,
        @Schema(nullable = true) AssignmentSummary assignment,
        @Schema(description = "明示公開許可と正式master/質問/素材登録が揃う時だけtrue。育成・報酬停止とは独立")
        boolean approvedVisualsAvailable) {
    /** 旧ACK・既存fixtureの互換形。追加fieldを持たない過去JSONはfalseになる。 */
    public RanchState(String featureStatus, boolean deliveryPaused, String rewardsStatus, boolean shopAvailable,
            CareBudget careBudget, OwnerSummary owner, DinosaurSummary dinosaur, RanchSettings settings,
            WeekBudget weekBudget, List<RoomSlotSummary> roomSlots, String policyVersion,
            Instant serverTime, AssignmentSummary assignment) {
        this(featureStatus, deliveryPaused, rewardsStatus, shopAvailable, careBudget, owner,
                dinosaur, settings, weekBudget, roomSlots, policyVersion, serverTime, assignment, false);
    }
}
