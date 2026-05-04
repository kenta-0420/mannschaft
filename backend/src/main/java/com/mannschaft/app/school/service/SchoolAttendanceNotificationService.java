package com.mannschaft.app.school.service;

import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * 保護者から欠席・遅刻連絡が送信されたときの担任向け通知。
     *
     * @param notice 送信された保護者連絡
     */
    public void notifyFamilyNoticeSubmitted(FamilyAttendanceNoticeEntity notice) {
        // TODO: NotificationDispatchService 経由で担任へプッシュ通知を送信する
        log.info("保護者連絡送信通知 noticeId={} studentUserId={} noticeType={}",
                notice.getId(), notice.getStudentUserId(), notice.getNoticeType());
    }

    /**
     * 担任が保護者連絡を確認済みにしたときの保護者向け通知。
     *
     * @param notice 確認済みになった保護者連絡
     */
    public void notifyFamilyNoticeAcknowledged(FamilyAttendanceNoticeEntity notice) {
        // TODO: NotificationDispatchService 経由で保護者へプッシュ通知を送信する
        log.info("保護者連絡確認通知 noticeId={} studentUserId={} acknowledgedBy={}",
                notice.getId(), notice.getStudentUserId(), notice.getAcknowledgedBy());
    }

    // ─── 出席要件警告通知（教員側のみ、生徒・保護者へは配信しない） ───

    /**
     * 出席要件 WARNING 水準到達時の担任・副担任向け通知。
     *
     * @param studentUserId  対象生徒のユーザーID
     * @param ruleName       規程名
     * @param teacherUserIds 通知先教員のユーザーIDリスト
     */
    public void notifyRequirementWarning(Long studentUserId, String ruleName, List<Long> teacherUserIds) {
        log.info("出席要件WARNING通知（教員向け）: studentUserId={}, rule={}, recipients={}",
                 studentUserId, ruleName, teacherUserIds);
        // TODO: NotificationDispatchService 経由でプッシュ通知を送信する
    }

    /**
     * 出席要件 RISK 水準到達時の担任・副担任・教科担任・教務向け通知。
     *
     * @param studentUserId  対象生徒のユーザーID
     * @param ruleName       規程名
     * @param teacherUserIds 通知先教員のユーザーIDリスト
     */
    public void notifyRequirementRisk(Long studentUserId, String ruleName, List<Long> teacherUserIds) {
        log.info("出席要件RISK通知（教員向け）: studentUserId={}, rule={}, recipients={}",
                 studentUserId, ruleName, teacherUserIds);
        // TODO: NotificationDispatchService 経由でプッシュ通知を送信する
    }

    /**
     * 出席要件 VIOLATION 確定時の担任・教務向け通知。
     *
     * @param studentUserId  対象生徒のユーザーID
     * @param ruleName       規程名
     * @param teacherUserIds 通知先教員のユーザーIDリスト
     */
    public void notifyRequirementViolation(Long studentUserId, String ruleName, List<Long> teacherUserIds) {
        log.info("出席要件VIOLATION通知（教員向け）: studentUserId={}, rule={}, recipients={}",
                 studentUserId, ruleName, teacherUserIds);
        // TODO: NotificationDispatchService 経由でプッシュ通知を送信する
    }

    /**
     * 週次ダイジェスト：担任へチームのリスク生徒一覧を通知。
     *
     * @param teamId                  チームID
     * @param atRiskCount             リスクあり生徒数
     * @param homeroomTeacherUserId   担任のユーザーID
     */
    public void sendWeeklyRiskDigest(Long teamId, int atRiskCount, Long homeroomTeacherUserId) {
        log.info("週次リスクダイジェスト（担任向け）: teamId={}, atRiskCount={}, teacherUserId={}",
                 teamId, atRiskCount, homeroomTeacherUserId);
        // TODO: NotificationDispatchService 経由でプッシュ通知を送信する
    }
}
