package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F03.16 スレッド状態レスポンス（設計書 §4.4）。
 *
 * <p>{@code canPostReason} は {@code canPost=false} のときのみ非 null（{@code CLOSED} /
 * {@code CANCELLED} / {@code ROLE}）。{@code canPost=true} のときは応答から省略する。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ThreadMetaResponse {
    private final Long scheduleId;
    private final boolean commentsEnabled;
    private final boolean canPost;

    @Schema(nullable = true, description = "canPost=false の理由（CLOSED/CANCELLED/ROLE）。canPost=true は null")
    private final String canPostReason;
}
