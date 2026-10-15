package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * ガントバー表示用TODOレスポンスDTO。
 * startDate・dueDate が両方設定されているTODOのみ対象とする。
 */
@Getter
@RequiredArgsConstructor
public class GanttTodoResponse {

    /** TODO ID。 */
    private final Long id;

    /** タイトル。 */
    private final String title;

    /** 開始日。 */
    private final LocalDate startDate;

    /** 期限日。 */
    private final LocalDate dueDate;

    /** 進捗率（0.00〜100.00）。 */
    private final BigDecimal progressRate;

    /** 進捗率が手動設定かどうか。 */
    private final Boolean progressManual;

    /** ステータス（OPEN / IN_PROGRESS / COMPLETED）。 */
    private final String status;

    /** 優先度（LOW / MEDIUM / HIGH / URGENT）。 */
    private final String priority;

    /** 親TODO ID（nullの場合はルートTODO）。 */
    private final Long parentId;

    /** 階層の深さ（0がルート）。インデント表示に使用する。 */
    private final Integer depth;

    /** 直接の子TODO IDリスト。クライアント側での階層構築に使用する。 */
    private final List<Long> childIds;
}
