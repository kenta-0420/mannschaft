package com.mannschaft.app.ranch.dto;

import java.time.Instant;

/** 次UTC週以降にだけ新規公開できる不変care規則。BIGINTはdecimal string。 */
public record RanchCareRulePublicationRequest(Instant effectiveAt, String amountXp,
        String weeklyCapXp, String juvenileXp, String adultXp, String reasonCode) { }