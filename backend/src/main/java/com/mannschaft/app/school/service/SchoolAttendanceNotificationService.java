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
 *
 * <h2>⚠ Phase 3 でこのクラスの TODO を実装する人へ（必読）</h2>
 *
 * <p><b>この経路のトランザクション境界は Issue #2990 / PR #3116 で既に正してある。
 * 通知の中身を書くときに、その構造を壊さないこと。</b></p>
 *
 * <p>現在の構造は次のとおりである。</p>
 * <ol>
 *   <li>業務サービス（{@code DailyAttendanceService} /
 *       {@code FamilyAttendanceNoticeService} / {@code AttendanceRequirementBatchService}）は
 *       業務トランザクションの内側では<b>ID だけを載せたイベントを publish するのみ</b>で、
 *       通知の送信は一切しない。</li>
 *   <li>実際の配送は {@link com.mannschaft.app.school.listener.SchoolAttendanceNotificationListener} が
 *       {@code @TransactionalEventListener(phase = AFTER_COMMIT)} +
 *       {@code @Async("event-pool")} で受け取り、業務トランザクションの<b>外側・別スレッド</b>で行う。</li>
 * </ol>
 *
 * <p>したがって<b>このクラスのメソッド本体に実際の通知送信を実装しても、
 * 呼び出し元の業務トランザクションは巻き戻らない</b>（例外は AFTER_COMMIT の非同期スレッドで閉じる）。
 * 安心して TODO を埋めてよい。</p>
 *
 * <h3>やってはいけないこと</h3>
 * <p><b>「業務サービスからこのクラスを直接同期で呼ぶ」形に戻してはならない。</b>
 * 戻すと通知の失敗（プッシュ基盤のタイムアウト・保護者 userId の解決失敗・
 * テンプレート未登録による例外など、いずれも Phase 3 で新たに増える失敗要因である）で
 * 業務処理ごと巻き戻る。具体的には:</p>
 * <ul>
 *   <li>{@code DailyAttendanceService#submitDailyRollCall} — <b>クラス全員ぶんの
 *       {@code daily_attendance_records} が全件消える。</b>
 *       教員が点呼を送信し画面上は完了したように見えて、1 人ぶんの保護者通知が失敗しただけで
 *       クラス全員の出欠が保存されていない、という壊れ方をする。</li>
 *   <li>{@code FamilyAttendanceNoticeService#submitNotice} / {@code #acknowledgeNotice} —
 *       保護者が送信した欠席・遅刻連絡そのもの、あるいは担任の「確認済み」操作が保存されない。</li>
 *   <li>{@code AttendanceRequirementBatchService} の要件評価 —
 *       WARNING / RISK / VIOLATION の評価結果の保存が巻き戻り、
 *       次回バッチまで出席要件の判定が古いままになる。</li>
 * </ul>
 * <p>通知は業務データより重要度が低い。<b>通知が失敗しても業務データは残る</b>——
 * この非対称性が上記の構造の目的である。</p>
 *
 * <h3>番人はこの退行を検出できない</h3>
 * <p>{@code NotificationTransactionBoundaryGuardTest} の凍結台帳
 * （{@code notification_tx_boundary_freeze.txt}）は<b>新規の違反</b>を止める仕組みであり、
 * <b>既に台帳に載っているエントリがスタブから本実装に変わっても件数は増減しないため、番人は何も言わない</b>。
 * つまりこのクラスの TODO を埋める作業は、CI が黙ったまま巻き戻りを再導入しうる。
 * 唯一の防御はこの警告と、レビュー時に上記の構造を確認することである。
 * 経緯と検出できない理由の詳細は <b>Issue #3117</b> を参照。</p>
 *
 * @see com.mannschaft.app.school.listener.SchoolAttendanceNotificationListener
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
        // ⚠ Phase 3 の実装者へ: ここに送信処理を書くのは安全である（呼び出し元は
        //   SchoolAttendanceNotificationListener の AFTER_COMMIT + @Async("event-pool") であり業務TXの外側）。
        //   ただし DailyAttendanceService#submitDailyRollCall から直接同期で呼ぶ形に戻すと、
        //   通知失敗でクラス全員ぶんの daily_attendance_records が全件巻き戻る。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #2990 / #3116 / #3117 を必ず読むこと。
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
        // ⚠ Phase 3 の実装者へ: 呼び出し元は SchoolAttendanceNotificationListener の
        //   AFTER_COMMIT + @Async であり、ここでの失敗は業務TXを巻き戻さない。
        //   FamilyAttendanceNoticeService#submitNotice から直接同期で呼ぶ形に戻すと、
        //   通知失敗で保護者が送信した欠席・遅刻連絡そのものが保存されなくなる。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
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
        // ⚠ Phase 3 の実装者へ: 呼び出し元は SchoolAttendanceNotificationListener の
        //   AFTER_COMMIT + @Async であり、ここでの失敗は業務TXを巻き戻さない。
        //   FamilyAttendanceNoticeService#acknowledgeNotice から直接同期で呼ぶ形に戻すと、
        //   通知失敗で担任の「確認済み」操作が保存されなくなる。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
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
        // ⚠ Phase 3 の実装者へ: 呼び出し元は SchoolAttendanceNotificationListener の
        //   AFTER_COMMIT + @Async であり、ここでの失敗は業務TXを巻き戻さない。
        //   AttendanceRequirementBatchService から直接同期で呼ぶ形に戻すと、
        //   通知失敗で要件評価（WARNING/RISK/VIOLATION）の保存ごと巻き戻る。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
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
        // ⚠ Phase 3 の実装者へ: 呼び出し元は SchoolAttendanceNotificationListener の
        //   AFTER_COMMIT + @Async であり、ここでの失敗は業務TXを巻き戻さない。
        //   AttendanceRequirementBatchService から直接同期で呼ぶ形に戻すと、
        //   通知失敗で要件評価（WARNING/RISK/VIOLATION）の保存ごと巻き戻る。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
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
        // ⚠ Phase 3 の実装者へ: 呼び出し元は SchoolAttendanceNotificationListener の
        //   AFTER_COMMIT + @Async であり、ここでの失敗は業務TXを巻き戻さない。
        //   AttendanceRequirementBatchService から直接同期で呼ぶ形に戻すと、
        //   通知失敗で要件評価（WARNING/RISK/VIOLATION）の保存ごと巻き戻る。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
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
        // ⚠ Phase 3 の実装者へ（このメソッドだけ事情が異なる。特に注意）:
        //   本メソッドは他の 5 つと違い、いまも AttendanceRequirementBatchService#sendWeeklyDigest から
        //   @Transactional(readOnly = true) のバッチTX内で直接同期に呼ばれている
        //   （PR #3116 の是正時、番人の語彙外で発火点として数えられていなかったため移設対象外だった）。
        //   readOnly なので書き込みの巻き戻りは起きないが、ここで例外を投げるとループが中断し、
        //   以降のチームの週次ダイジェストが丸ごと送信されない。
        //   実装時は「1 チームの送信失敗でループを止めない」ことを保証するか、
        //   他の 5 つと同様にイベント publish + AFTER_COMMIT リスナーへ移設すること。
        //   番人はこの退行を検出できない。クラス javadoc と Issue #3117 を参照。
    }
}
