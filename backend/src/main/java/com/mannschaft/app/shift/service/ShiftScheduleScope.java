package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftScheduleStatus;

import java.time.LocalDateTime;

/**
 * シフトスケジュールの scope（認可ファサードが認可の前に読む最小の情報）。
 *
 * <p>CMP-260923-0954 W6a: Facade（トランザクションの外）が認可を行うために、スケジュール実体から
 * 解決した所属チーム ID と公開状態を渡す。Entity は公開メソッドの戻り値にできない
 * （{@code ServiceApiEntityBoundaryArchTest}）ので、値だけの record で返す。</p>
 *
 * @param scheduleId  スケジュール ID
 * @param teamId      所属チーム ID
 * @param status      ステータス
 * @param publishedAt 公開日時（未公開は null）
 */
public record ShiftScheduleScope(Long scheduleId, Long teamId, ShiftScheduleStatus status,
                                 LocalDateTime publishedAt) {

    /** 公開状態の分類を返す。 */
    public ShiftScheduleVisibilityPolicy.Visibility visibility() {
        return ShiftScheduleVisibilityPolicy.classify(status, publishedAt);
    }

    /** 閲覧者に対して存在ごと秘匿すべき（未公開）かを返す。 */
    public boolean isHidden() {
        return visibility().isHidden();
    }
}
