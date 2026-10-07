package com.mannschaft.app.activity.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** 内部詳細に限って関連情報を追加し、一覧・公開の契約を保全するレスポンス。 */
@Getter
@RequiredArgsConstructor
public class ActivityDetailResponse {
    @JsonUnwrapped
    private final ActivityRecordResponse record;
    private final String scopePublicId;
    private final LocalDate activityEndDate;
    private final Long version;
    private final boolean canEdit;
    private final boolean canPublish;
    private final ActivitySourceScheduleResponse sourceSchedule;
    private final List<ActivityParticipantResponse> participants;
    private final List<ActivityTemplateResponse.TemplateFieldResponse> templateFields;
}
