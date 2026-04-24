package com.mannschaft.app.jobmatching.controller.dto;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * QR トークン発行リクエスト。F13.1 Phase 13.1.2。
 *
 * <p>{@code POST /api/v1/contracts/{id}/qr-tokens} で Requester が送信する。
 * {@code ttlSeconds} は省略可能で、省略時は {@code QrSigningProperties#getTtlSeconds()}（既定 60 秒）。
 * 上限は {@code QrSigningProperties#getTtlSecondsMax()}（既定 300 秒）で Service 側でもクランプされるが、
 * Controller 側でも明らかに不正な値を弾く意味で {@code @Max(300)} を付与する。</p>
 *
 * @param type       IN / OUT 種別（必須）
 * @param ttlSeconds TTL（秒、nullable。未指定なら設定値、上限は 300 秒）
 */
public record IssueQrTokenRequest(
        @NotNull JobCheckInType type,
        @Min(1) @Max(300) Integer ttlSeconds
) {
}
