package com.mannschaft.app.billing.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Billing Center PR6b-1 B群: 変更の実行（{@code POST …/changes}）の要求本文。
 *
 * @param previewId 消費対象の preview（AC-7: 一回だけ消費できる）
 * @param version   利用者が把握している契約 {@code version}（AC-12 の CAS）
 */
public record BillingPlanChangeRequest(@NotNull UUID previewId, @NotNull Long version) {
}
