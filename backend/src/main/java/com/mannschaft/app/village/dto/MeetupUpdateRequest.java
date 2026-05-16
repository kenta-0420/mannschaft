package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * F17.1 Phase 3-β — 寄合更新リクエスト。
 *
 * <p>すべて optional。{@code null} のフィールドは更新対象外。
 * 候補日の追加/削除は別 API ({@code addCandidateDate} / {@code removeCandidateDate}) を使う。</p>
 */
public record MeetupUpdateRequest(
        @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Size(max = 300) String location) {
}
