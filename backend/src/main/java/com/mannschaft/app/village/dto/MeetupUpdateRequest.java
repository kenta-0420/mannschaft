package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Size;

/**
 * F17.1 Phase 3-β — 寄合更新リクエスト。
 *
 * <p>すべて optional。{@code null} のフィールドは更新対象外。
 * 候補日の追加/削除は別 API ({@code addCandidateDate} / {@code removeCandidateDate}) を使う。</p>
 *
 * <p>F17.2 Wave1 ②寄合後半戦（設計書 §4.2.4/§4.4/AC-13）: {@code decisionsNote}（決まったこと）を追加。
 * このフィールドは <strong>幹事＋村長/長老</strong> が CONFIRMED 状態で更新できる（他フィールドの
 * 「幹事限定・PLANNING 限定」ガードとは<strong>独立</strong>に判定する。サービス層 {@code updateMeetup} 参照）。</p>
 */
public record MeetupUpdateRequest(
        @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Size(max = 300) String location,
        @Size(max = 5000) String decisionsNote) {
}
