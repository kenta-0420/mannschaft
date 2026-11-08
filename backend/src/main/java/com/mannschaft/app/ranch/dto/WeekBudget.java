package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 活動ポイント週枠。careとは独立し、未登録policyではnull。 */
public record WeekBudget(LocalDate weekStartsOn, Instant weekEndsAt, String globalCap,
        String awardedTotal, String remaining, String policyVersion,
        String personalRequiredCount, int personalCompletedCount) { }
