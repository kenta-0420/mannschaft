package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * シフト希望未提出者への手動リマインド送信レスポンス（F03.5 Phase 11）。
 */
@Getter
@Builder
public class ManualRemindResponse {

    /** 対象スケジュール ID */
    private final Long scheduleId;

    /** リマインド送信対象の未提出メンバー数。 */
    private final Integer remindedCount;

    /** リマインド送信対象の未提出メンバー ID 一覧。 */
    private final List<Long> remindedUserIds;
}
