package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.CalendarEntryResponse;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 横断カレンダー（{@code GET /api/v1/my/calendar}）への追加合流 SPI（F06.5 §6.2）。
 *
 * <p>{@link ScheduleQueryService#getMyCalendar} の <b>return 直前に独立 enrich パス</b>として、
 * schedule 以外のドメイン（例 reflection）が自前の可視性フィルタを通したカレンダー印を
 * 追加するための拡張口。既存 schedule 合流（Long 経路）は一切改変せず、各 enricher は
 * 自ドメインの UUID 経路フィルタ（F00 {@code filterAccessibleUuid}）を通した
 * {@link CalendarEntryResponse}（{@code id=null} ＋ {@code content.referenceUuid} 識別）を返す。</p>
 *
 * <p>schedule ドメインは具体実装に依存せず、Spring が収集した {@code List<CalendarEnricher>} を
 * 注入する（実装が無ければ空リスト）。これにより schedule → reflection の逆方向依存を作らない。</p>
 */
public interface CalendarEnricher {

    /**
     * 指定ユーザー・期間のカレンダー印を返す。
     *
     * @param userId 閲覧者（＝本人）ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return 追加するカレンダーエントリ（可視性フィルタ適用済み・空可）
     */
    List<CalendarEntryResponse> enrich(Long userId, LocalDateTime from, LocalDateTime to);
}
