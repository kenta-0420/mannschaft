package com.mannschaft.app.shift.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * シフトスケジュールが「公開状態でなくなった」ことを表すイベント（CMP-260909-1445）。
 *
 * <p>{@link ShiftArchivedEvent} が ARCHIVED 遷移だけを表すのに対し、本イベントは
 * <b>アーカイブ以外</b>で公開が失効する経路を表す。現時点では 2 つある。</p>
 * <ul>
 *   <li>{@link ShiftScheduleCloseReason#DELETED} — {@code deleteSchedule} による論理削除</li>
 *   <li>{@link ShiftScheduleCloseReason#UNPUBLISHED} — PUBLISHED から COLLECTING / ADJUSTING への
 *       後戻り遷移（公開取消）。{@code ShiftScheduleEntity} の遷移メソッドは status を無条件に
 *       上書きするだけでガードを持たないため、この後戻りは実際に成立する</li>
 * </ul>
 *
 * <p>いずれの経路でも、公開時に {@code ShiftPublishedEvent} を契機として積まれた
 * シフト予算の PLANNED 消化を残してはならない。残すと当該 allocation が
 * {@code SHIFT_BUDGET_012}（PLANNED 消化残存）で恒久的に削除不能になる。</p>
 *
 * <p>ARCHIVED を本イベントに含めない理由: ARCHIVED は Todo 自動キャンセル・変更依頼の
 * 自動 WITHDRAWN 化という別の購読者も持つ既存イベント {@link ShiftArchivedEvent} が
 * 表現しており、両方を発行すると同じ消化取消が二重に走る（{@code cancelAllForShift} は
 * 冪等なので実害は無いが、意図が読めなくなる）。経路とイベントは 1 対 1 に保つ。</p>
 */
@Getter
public class ShiftScheduleClosedEvent extends BaseEvent {

    private final Long scheduleId;
    private final Long teamId;
    /** 操作者のユーザーID。バッチ等の自動処理では null。 */
    private final Long actorUserId;
    private final ShiftScheduleCloseReason reason;

    public ShiftScheduleClosedEvent(Long scheduleId, Long teamId, Long actorUserId,
                                    ShiftScheduleCloseReason reason) {
        super();
        this.scheduleId = scheduleId;
        this.teamId = teamId;
        this.actorUserId = actorUserId;
        this.reason = reason;
    }
}
