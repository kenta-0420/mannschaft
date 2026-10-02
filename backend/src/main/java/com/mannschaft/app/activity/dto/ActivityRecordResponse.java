package com.mannschaft.app.activity.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 活動記録レスポンスDTO。
 *
 * <p>{@code ActivityResultEntity} の Entity 直返しを解消するための DTO 化（活動記録 API
 * レスポンス契約テスト {@code ActivityResponseContractTrialTest} 参照）。現行 JSON 形をそのまま
 * 写像し、{@code deletedAt}（内部フィールド）と {@code publishable}（{@code isPublishable()}
 * 派生ゲッター）は意図的に含めない。</p>
 */
@Builder
@Getter
public class ActivityRecordResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long templateId;
    private final String title;
    private final LocalDate activityDate;
    private final LocalTime activityTimeStart;
    private final LocalTime activityTimeEnd;
    private final String location;
    private final Long venueId;
    private final String description;

    /** JSON 文字列型のまま保持する（オブジェクトへの展開はしない）。 */
    private final String fieldValues;

    /** JSON 文字列型のまま保持する（オブジェクトへの展開はしない）。 */
    private final String attachments;

    private final String visibility;
    private final String status;
    private final Long scheduleId;

    /** 作成者のユーザー ID をそのまま保持する（ユーザー情報への解決はしない）。 */
    private final Long createdBy;

    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
