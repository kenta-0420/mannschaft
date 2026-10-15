package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * カレンダーエントリーレスポンスDTO。横断カレンダー表示用。
 */
@Builder(toBuilder = true)
@Getter
public class CalendarEntryResponse {

    Long               id;

    /**
     * 親 {@code schedules} 行の ID（設計書 §1.5 / AC-07(b)）。
     *
     * <p>本エントリが実体としてスケジュール行そのものである場合は {@code id} と同値になる。
     * reflection 等 UUID 主キードメイン由来のエントリ（{@code id=null}）では常に {@code null}。
     * 将来 F03.8 イベント（{@code events.schedule_id} が NULL 許容）がカレンダーに合流した際、
     * 親スケジュール不在のイベントを FE が判別してコメント欄を非表示にするための専用フィールド。</p>
     */
    Long               scheduleId;
    CalendarContentDto content;  // title, eventType, status
    CalendarTimeDto    time;     // startAt, endAt, allDay
    CalendarScopeDto   scope;    // scopeType, scopeId, scopeName, scopeIconUrl
    String             myAttendanceStatus;
    String             targetMode;
    Integer            targetCount;
    List<ScheduleTargetResponse.TargetMember> targets;

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
     * @param color         §3.4 で解決済みの最終表示色（{@code #RRGGBB}）。応答経路では非 null（AC-18b）
     * @param colorSource   色の由来（§4.3.2 の共通4値）。応答経路では非 null（AC-18b）
     * @param categoryColor カテゴリ色そのもの（カテゴリ無し・未設定は null）
     */
    public record CalendarContentDto(String title, String eventType, String status,
                                     String referenceUuid, String referenceKind,
                                     String color, CalendarColorSource colorSource,
                                     String categoryColor) {

        /**
         * 5 引数の後方互換コンストラクタ（F03.19 §4.6 / R8-2）。
         *
         * <p>正準コンストラクタが 8 引数へ広がったことで<b>暗黙に存在していた 5 引数版が消える</b>ため、
         * 明示コンストラクタとして書き足したものである。色は {@code null} のまま構築されるので、
         * {@code GET /my/calendar} の応答経路では<b>サービス層が必ず色を埋める</b>こと
         * （色 null のまま返してはならない・AC-18b）。</p>
         */
        public CalendarContentDto(String title, String eventType, String status,
                                  String referenceUuid, String referenceKind) {
            this(title, eventType, status, referenceUuid, referenceKind, null, null, null);
        }

        /** 既存 schedule 行用の後方互換コンストラクタ（referenceUuid/referenceKind=null）。 */
        public CalendarContentDto(String title, String eventType, String status) {
            this(title, eventType, status, null, null);
        }

        /** 色だけを差し替えた複製を返す（サービス層が色を後付けするための手段・R14）。 */
        public CalendarContentDto withColor(String newColor, CalendarColorSource newSource,
                                            String newCategoryColor) {
            return new CalendarContentDto(title, eventType, status, referenceUuid, referenceKind,
                    newColor, newSource, newCategoryColor);
        }
    }

    public record CalendarTimeDto(LocalDateTime startAt, LocalDateTime endAt, Boolean allDay) {
    }

    /** チーム・組織のアイコン画像URL。未設定またはPERSONALスコープの場合はnull。 */
    public record CalendarScopeDto(String scopeType, Long scopeId, String scopeName,
                                   String scopeIconUrl, String scopeSlug) {

        /** 既存呼び出し元との後方互換。PERSONAL・外部enricherはslugを持たなくてもよい。 */
        public CalendarScopeDto(String scopeType, Long scopeId, String scopeName, String scopeIconUrl) {
            this(scopeType, scopeId, scopeName, scopeIconUrl, null);
        }
    }
}
