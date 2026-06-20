package com.mannschaft.app.reflection.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

/**
 * 今日の振り返りビュー（F06.5・§4.3 / §7 #12）。
 *
 * <p>{@code items} には「時間割コマ由来 item」（{@code slotKind}/{@code slotId} を持つ・実引き当てキーは
 * source_kind(String)/slot_id(Long)）と「自由テーマ由来 item」（{@code slotKind=null}・PROJECT/DIARY/FREE）が
 * 混在する。FE は {@code slotKind} の有無で描き分ける（§4.3）。</p>
 *
 * @param date  対象日（ユーザー TZ の今日 or ?date=）
 * @param items 今日のコマ／テーマ item 群
 */
@Builder
public record ReflectionTodayResponse(
        LocalDate date,
        List<ReflectionTodayItem> items
) {

    /**
     * 今日ビューの 1 item（コマ or 自由テーマ）。
     *
     * @param slotKind      時間割スロット種別（"TEAM"/"PERSONAL"・自由テーマ由来は null）
     * @param slotId        時間割スロットID（自由テーマ由来は null）
     * @param periodLabel   時限ラベル（例 "1限"・自由テーマ由来は null）
     * @param subjectName   科目名／コマ名（自由テーマ由来はテーマ名）
     * @param themeId       対応するテーマID（未設定の空きコマは null）
     * @param hasEntryToday 当日エントリが存在するか
     * @param entryId       当日エントリID（無ければ null）
     * @param isMasked      当日エントリがマスク中か（当日は通常 false・§3.1 step0）
     */
    @Builder
    public record ReflectionTodayItem(
            String slotKind,
            Long slotId,
            String periodLabel,
            String subjectName,
            String themeId,
            boolean hasEntryToday,
            String entryId,
            boolean isMasked
    ) {
    }
}
