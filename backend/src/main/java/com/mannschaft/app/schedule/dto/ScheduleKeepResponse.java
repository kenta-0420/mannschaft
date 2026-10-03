package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * キープ（日付未定の予定）レスポンスDTO（F03.17 §4.2）。
 *
 * <p>{@code memo}/{@code candidateDates}/{@code convertedScheduleId}/{@code createdBy} は
 * 未設定なら {@code null} を返す（空配列・空文字にしない・§3.4）。
 * クラス単位 {@link JsonInclude}（{@code NON_NULL}）でシリアライズ時に省略する。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleKeepResponse {

    /** UUIDv7 の canonical 文字列表現（§3.2.1）。 */
    private final String id;

    /** {@code TEAM}/{@code ORGANIZATION}/{@code PERSONAL}。 */
    private final String scopeType;

    private final String teamPublicId;

    private final String organizationPublicId;

    private final String title;

    private final String memo;

    /** 昇順ソート・重複除去済み。候補日なしは {@code null}（§3.4）。 */
    private final List<String> candidateDates;

    /** {@code KEPT}/{@code SCHEDULED}/{@code ARCHIVED}。 */
    private final String status;

    private final Long convertedScheduleId;

    /** {@code NONE}/{@code ACTIVE}/{@code CANCELLED}/{@code DELETED}（§5.4）。 */
    private final String convertedScheduleState;

    private final Integer sortOrder;

    private final CreatedByDto createdBy;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedByDto {
        private final Long userId;
        private final String displayName;
    }
}
