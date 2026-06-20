package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * カレンダーエントリーレスポンスDTO。横断カレンダー表示用。
 */
@Builder(toBuilder = true)
@Getter
public class CalendarEntryResponse {

    Long               id;
    CalendarContentDto content;  // title, eventType, status
    CalendarTimeDto    time;     // startAt, endAt, allDay
    CalendarScopeDto   scope;    // scopeType, scopeId, scopeName, scopeIconUrl
    String             myAttendanceStatus;

    /**
     * カレンダーエントリの内容。
     *
     * <p>{@code referenceUuid} / {@code referenceKind} は UUID 主キーのドメイン（例 reflection・F06.5 §6.2）が
     * 横断カレンダーに合流する際の識別子。既存 schedule 行（Long 主キー）は両者 {@code null} で構築し、
     * 識別は従来どおり {@link CalendarEntryResponse#id}（Long）で行う。reflection 行は {@code id=null} で
     * {@code referenceUuid} 非 null になる（§6.2）。API JSON は新フィールド増加のみで後方互換。</p>
     *
     * @param title         表示タイトル
     * @param eventType     イベント種別（例 "REFLECTION_ENTRY" / "REFLECTION_RECALL"）
     * @param status        ステータス（reflection 行は null）
     * @param referenceUuid UUID 主キードメインの識別子（schedule 行は null）
     * @param referenceKind 参照種別（例 "REFLECTION_ENTRY" / "REFLECTION_RECALL"・schedule 行は null）
     */
    public record CalendarContentDto(String title, String eventType, String status,
                                     String referenceUuid, String referenceKind) {

        /** 既存 schedule 行用の後方互換コンストラクタ（referenceUuid/referenceKind=null）。 */
        public CalendarContentDto(String title, String eventType, String status) {
            this(title, eventType, status, null, null);
        }
    }

    public record CalendarTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    /** チーム・組織のアイコン画像URL。未設定またはPERSONALスコープの場合はnull。 */
    public record CalendarScopeDto(String scopeType, Long scopeId, String scopeName,
                                   String scopeIconUrl) {
    }
}
