package com.mannschaft.app.school.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.school.entity.AttendanceRequirementRuleEntity;
import com.mannschaft.app.school.entity.DailyAttendanceRecordEntity;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.event.AttendanceRequirementStatusChangedEvent;
import com.mannschaft.app.school.event.DailyRollCallRecordedEvent;
import com.mannschaft.app.school.event.FamilyAttendanceNoticeAcknowledgedEvent;
import com.mannschaft.app.school.event.FamilyAttendanceNoticeSubmittedEvent;
import com.mannschaft.app.school.repository.AttendanceRequirementRuleRepository;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import com.mannschaft.app.school.repository.DailyAttendanceRecordRepository;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
import com.mannschaft.app.school.service.SchoolAttendanceNotificationService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 学校出欠まわりの付随通知を業務コミット後に配送するリスナー（Issue #2990 L6 TX_NOTIFY_BARE 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前は 4 つの業務メソッドが {@code @Transactional} の内側から
 * {@link SchoolAttendanceNotificationService} を直接呼んでいた。同サービスは {@code @Transactional}
 * を宣言していないため既定の {@code REQUIRED} で呼び出し元の業務トランザクションに参加する。
 * したがって通知側が例外を投げれば、そのまま業務トランザクションへ伝播して業務処理ごと巻き戻る。
 * 巻き戻る内容は次のとおり。</p>
 * <ul>
 *   <li>{@code DailyAttendanceService#submitDailyRollCall} — <b>被害が最も大きい</b>。
 *       通知呼び出しが生徒ごとのループの内側にあり、try で囲われてもいない。
 *       生徒 1 人ぶんの保護者通知が失敗すると、その朝クラス全員ぶんに書いた
 *       {@code daily_attendance_records} 行（新規・更新とも）が<b>全件</b>巻き戻る。
 *       担任の点呼作業 1 回ぶんが丸ごと消える。</li>
 *   <li>{@code FamilyAttendanceNoticeService#submitNotice} — 保護者が送った欠席・遅刻連絡
 *       （{@code family_attendance_notices} 行、暗号化された理由詳細・添付キーを含む）が消える。
 *       保護者は送ったつもりでいるのに担任には届かない。</li>
 *   <li>{@code FamilyAttendanceNoticeService#acknowledgeNotice} — 担任の確認済み化
 *       （{@code acknowledged_by} / {@code acknowledged_at}）が巻き戻り、連絡は未確認のまま残る。</li>
 *   <li>{@code AttendanceRequirementBatchService#runDailyEvaluation} — バッチ全体が単一の
 *       {@code @Transactional} である。生徒ごとの try は例外そのものは握るが、
 *       通知側が実 DB エラーを起こした場合はトランザクションが rollback-only になり、
 *       commit 時に {@code UnexpectedRollbackException} でその日の評価結果
 *       （{@code attendance_requirement_evaluations}）が<b>全生徒・全規程ぶん</b>巻き戻る。</li>
 * </ul>
 *
 * <h2>現時点の実害と、それでも是正する理由（隠さず書く）</h2>
 * <p>{@link SchoolAttendanceNotificationService} は 2026-09-04 時点で<b>全メソッドが
 * {@code log.info} / {@code log.debug} だけのスタブ</b>であり、DB 書き込みも配信も行わない
 * （各メソッドに「Phase 3 で {@code NotificationDispatchService} 経由の送信を実装する」TODO が付いている）。
 * すなわち<b>今日の本番で実際に巻き戻ることはない</b>。是正の目的は、その TODO が実装された瞬間に
 * 上記の巻き戻りが一斉に顕在化するのを、実装される前に構造として塞いでおくことである。
 * 通知本文の組み立てを後から足しても境界は正しいままになる。</p>
 *
 * <h2>是正後</h2>
 * <p>業務サービスは ID だけを載せたイベントを publish するに留め、本リスナーが
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取って業務データを読み直し、
 * {@link SchoolAttendanceNotificationService} を呼ぶ。受信者ごとの失敗は
 * ループ内 {@code try/catch} で隔離し、ERROR ログに対象 ID を残す
 * （{@code EventCareNotificationTriggerListener}（#2990 L5）と同型）。</p>
 *
 * <h2>{@link SchoolAttendanceNotificationService} は変更していない</h2>
 * <p>同サービスは通知を送るのが仕事のクラスである。変えたのは<b>呼び出し位置</b>だけで、
 * 通知の判定（{@code UNDECIDED} は送らない等）も本文の責務もすべて同サービスに残している。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchoolAttendanceNotificationListener {

    private final SchoolAttendanceNotificationService notificationService;
    private final DailyAttendanceRecordRepository dailyAttendanceRecordRepository;
    private final FamilyAttendanceNoticeRepository familyAttendanceNoticeRepository;
    private final AttendanceRequirementRuleRepository ruleRepository;
    private final ClassHomeroomRepository homeroomRepository;

    /**
     * 朝の点呼一括登録後の保護者通知を配送する。
     *
     * <p>レコードの読み直しは全体で 1 回、通知は生徒 1 人ずつ try で隔離する。
     * 1 人の失敗が他の生徒の保護者通知を巻き添えにしない。</p>
     *
     * @param event 点呼登録完了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "学校の出欠通知は出欠記録（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと保護者は子が登校したか欠席扱いかを知る手段を失い、担任は保護者連絡が"
                    + "届いたことに気付けない。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDailyRollCallRecorded(DailyRollCallRecordedEvent event) {
        List<Long> recordIds = event.recordIds();
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }

        List<DailyAttendanceRecordEntity> records;
        try {
            records = dailyAttendanceRecordRepository.findAllById(recordIds);
        } catch (Exception e) {
            // 読み直しに失敗したら配送は中止する。握りつぶさず ERROR で残す。
            log.error("日次点呼の保護者通知: 出欠レコードの読み直しに失敗したため配送を中止します: "
                    + "teamId={}, recordIds={}", event.teamId(), recordIds, e);
            return;
        }

        if (records.size() < recordIds.size()) {
            // 業務TX でコミットされたはずの行が引けない＝異常。落ちた分は通知されないので記録する。
            log.error("日次点呼の保護者通知: 読み直せた出欠レコードが要求より少ないです: "
                            + "teamId={}, requested={}, resolved={}",
                    event.teamId(), recordIds.size(), records.size());
        }

        int failed = 0;
        Long firstFailedStudentUserId = null;
        for (DailyAttendanceRecordEntity record : records) {
            try {
                notificationService.notifyDailyAttendance(
                        record.getStudentUserId(), record.getAttendanceDate(), record.getStatus());
            } catch (Exception e) {
                failed++;
                if (firstFailedStudentUserId == null) {
                    firstFailedStudentUserId = record.getStudentUserId();
                }
                // 非同期イベント失敗の監査記録（規約上必須）。業務TX外なので rollback で消えない。
                log.error("日次点呼の保護者通知の配送に失敗しました: teamId={}, recordId={}, studentUserId={}",
                        event.teamId(), record.getId(), record.getStudentUserId(), e);
            }
        }

        if (failed > 0) {
            log.error("日次点呼の保護者通知の一括配送の結果: teamId={}, total={}, failed={}, "
                            + "firstFailedStudentUserId={}",
                    event.teamId(), records.size(), failed, firstFailedStudentUserId);
        }
    }

    /**
     * 保護者連絡の送信を担任へ通知する。
     *
     * @param event 保護者連絡送信イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "学校の出欠通知は出欠記録（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと保護者は子が登校したか欠席扱いかを知る手段を失い、担任は保護者連絡が"
                    + "届いたことに気付けない。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFamilyNoticeSubmitted(FamilyAttendanceNoticeSubmittedEvent event) {
        findNoticeForDelivery(event.noticeId(), "保護者連絡送信通知")
                .ifPresent(notice -> {
                    try {
                        notificationService.notifyFamilyNoticeSubmitted(notice);
                    } catch (Exception e) {
                        log.error("保護者連絡送信通知の配送に失敗しました: noticeId={}", event.noticeId(), e);
                    }
                });
    }

    /**
     * 担任による保護者連絡の確認を保護者へ通知する。
     *
     * @param event 保護者連絡確認イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "学校の出欠通知は出欠記録（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと保護者は子が登校したか欠席扱いかを知る手段を失い、担任は保護者連絡が"
                    + "届いたことに気付けない。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFamilyNoticeAcknowledged(FamilyAttendanceNoticeAcknowledgedEvent event) {
        findNoticeForDelivery(event.noticeId(), "保護者連絡確認通知")
                .ifPresent(notice -> {
                    try {
                        notificationService.notifyFamilyNoticeAcknowledged(notice);
                    } catch (Exception e) {
                        log.error("保護者連絡確認通知の配送に失敗しました: noticeId={}", event.noticeId(), e);
                    }
                });
    }

    /**
     * 出席要件の評価ステータス変化を教員へ通知する。
     *
     * <p>是正前は {@code AttendanceRequirementBatchService#notifyStatusChange}（private）が
     * 日次評価バッチの単一トランザクションの内側でこれを行っていた。</p>
     *
     * @param event ステータス変化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "学校の出欠通知は出欠記録（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと保護者は子が登校したか欠席扱いかを知る手段を失い、担任は保護者連絡が"
                    + "届いたことに気付けない。イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequirementStatusChanged(AttendanceRequirementStatusChangedEvent event) {
        AttendanceRequirementRuleEntity rule;
        try {
            rule = ruleRepository.findById(event.ruleId()).orElse(null);
        } catch (Exception e) {
            log.error("出席要件ステータス通知: 規程の読み直しに失敗したため配送を中止します: "
                    + "ruleId={}, studentUserId={}", event.ruleId(), event.studentUserId(), e);
            return;
        }
        if (rule == null) {
            log.error("出席要件ステータス通知: 規程が見つからないため配送を中止します: "
                    + "ruleId={}, studentUserId={}", event.ruleId(), event.studentUserId());
            return;
        }

        List<Long> teacherIds = resolveTeacherIds(rule);
        if (teacherIds.isEmpty()) {
            log.debug("出席要件ステータス通知: 通知先教員が解決できないためスキップ: ruleId={}", event.ruleId());
            return;
        }

        try {
            switch (event.newStatus()) {
                case WARNING -> notificationService.notifyRequirementWarning(
                        event.studentUserId(), rule.getName(), teacherIds);
                case RISK -> notificationService.notifyRequirementRisk(
                        event.studentUserId(), rule.getName(), teacherIds);
                case VIOLATION -> notificationService.notifyRequirementViolation(
                        event.studentUserId(), rule.getName(), teacherIds);
                default -> log.debug("通知対象外のステータス: ruleId={}, status={}",
                        event.ruleId(), event.newStatus());
            }
        } catch (Exception e) {
            log.error("出席要件ステータス通知の配送に失敗しました: ruleId={}, studentUserId={}, status={}",
                    event.ruleId(), event.studentUserId(), event.newStatus(), e);
        }
    }

    /**
     * 通知対象の保護者連絡を読み直す。読めない場合は配送中止として ERROR を残す。
     *
     * @param noticeId 保護者連絡ID
     * @param label    ログ用の通知種別ラベル
     * @return 読み直せた保護者連絡（読めなければ空）
     */
    private Optional<FamilyAttendanceNoticeEntity> findNoticeForDelivery(Long noticeId, String label) {
        if (noticeId == null) {
            return Optional.empty();
        }
        Optional<FamilyAttendanceNoticeEntity> notice;
        try {
            notice = familyAttendanceNoticeRepository.findById(noticeId);
        } catch (Exception e) {
            log.error("{}: 保護者連絡の読み直しに失敗したため配送を中止します: noticeId={}", label, noticeId, e);
            return Optional.empty();
        }
        if (notice.isEmpty()) {
            log.error("{}: 保護者連絡が見つからないため配送を中止します: noticeId={}", label, noticeId);
        }
        return notice;
    }

    /**
     * 規程から通知先教員（担任）のユーザーIDリストを解決する。
     *
     * <p>是正前の {@code AttendanceRequirementBatchService#getTeacherIds} から移設した。
     * 学年は {@code LocalDate.now()} ではなく規程の {@code academicYear} を使う
     * （評価対象年度と一致し、{@code DateTimeAndZoneGuardTest} の引数なし {@code now()} にも当たらない）。</p>
     *
     * @param rule 対象規程
     * @return 担任のユーザーIDリスト（解決できない場合は空リスト）
     */
    private List<Long> resolveTeacherIds(AttendanceRequirementRuleEntity rule) {
        if (rule.getTeamId() == null || rule.getAcademicYear() == null) {
            return List.of();
        }
        return homeroomRepository
                .findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
                        rule.getTeamId(), rule.getAcademicYear().intValue())
                .map(h -> List.of(h.getHomeroomTeacherUserId()))
                .orElse(List.of());
    }
}
