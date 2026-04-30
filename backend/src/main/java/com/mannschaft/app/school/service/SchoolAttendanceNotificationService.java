package com.mannschaft.app.school.service;

import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.schedule.AttendanceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 学校出欠通知サービス。
 *
 * <p>日次・時限別出欠の登録に伴う保護者・教員への通知を担当する。
 * 配信は NotificationDispatchService 経由。Phase 3 FE で通知テンプレート整備予定。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchoolAttendanceNotificationService {

    private final NotificationDispatchService notificationDispatchService;

    /**
     * 日次点呼後の保護者通知。
     *
     * <ul>
     *   <li>ATTENDING → 「お子さまが学校に到着しました」（常時送信）</li>
     *   <li>ABSENT → 「お子さまが本日欠席となっています」</li>
     *   <li>PARTIAL → 「お子さまが遅刻/早退として記録されました」</li>
     *   <li>UNDECIDED → 通知なし</li>
     * </ul>
     *
     * @param studentUserId 生徒のユーザーID
     * @param date          対象日
     * @param status        出欠ステータス
     */
    public void notifyDailyAttendance(Long studentUserId, LocalDate date, AttendanceStatus status) {
        if (status == AttendanceStatus.UNDECIDED) {
            return;
        }

        // 現段階ではログ出力のみ（通知テンプレートは Phase 3 FE で整備）
        // TODO: Phase 3 で user_care_links から保護者 userId を取得し、
        //       NotificationDispatchService 経由でプッシュ通知を実装する
        log.info("学校出欠通知: studentUserId={}, date={}, status={}", studentUserId, date, status);

        switch (status) {
            case ATTENDING ->
                log.debug("出席通知（保護者向け）: studentUserId={}, date={} — お子さまが学校に到着しました", studentUserId, date);
            case ABSENT ->
                log.debug("欠席通知（保護者向け）: studentUserId={}, date={} — お子さまが本日欠席となっています", studentUserId, date);
            case PARTIAL ->
                log.debug("遅刻/早退通知（保護者向け）: studentUserId={}, date={} — お子さまが遅刻/早退として記録されました", studentUserId, date);
            default -> {
                // UNDECIDED は上で return 済みのため到達しない
            }
        }
    }
}
