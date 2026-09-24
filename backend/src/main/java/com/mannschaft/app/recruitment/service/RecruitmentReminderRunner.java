package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.event.RecruitmentReminderNotificationEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.11 募集型予約リマインドの「1 件ぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット2。金型: {@code QuickMemoReminderRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code RecruitmentReminderBatch#reminderBatch} に {@code @Transactional} が付き、
 * 最大100件のリマインダー全体を 1 トランザクションで包んだまま 1 件ずつ catch していた。
 * 1 件の DB 例外が rollback-only を残すため、catch して続行した<b>他のリマインダーの
 * {@code sent_at} 更新もコミット時にまとめて巻き戻り</b>、1 分後の再実行で
 * 同じ相手へ二重にリマインドが飛びうる状態だった（通知だけは別トランザクションではないため
 * 同様に消えるが、{@code @Async} 配信は既に走っていることがある）。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>抽出時点のスナップショットを信じず、独立トランザクション内でリマインダーを読み直して
 * {@code sentAt == null} を再判定してから確定する。二重起動・再実行時に同じリマインダーを
 * 二度送信済みにすることはない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentReminderRunner {

    private final RecruitmentReminderRepository reminderRepository;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentParticipantRepository participantRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 件のリマインダーを独立トランザクションで送信済みに確定し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない（＝{@code sent_at} だけが残ることも、
     * 確定せずに通知だけ飛ぶこともない）。</p>
     *
     * <p>募集または参加者が既に削除されている場合は、是正前と同じく {@code sent_at} だけを
     * 確定して通知は publish しない（毎分の抽出で永久に拾い続けないため）。</p>
     *
     * @param reminderId リマインダーID
     * @return 通知配送要求を publish した場合は {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processOne(Long reminderId) {
        // 抽出後に他プロセスが確定している可能性があるため読み直す（冪等性の要）。
        RecruitmentReminderEntity reminder = reminderRepository.findById(reminderId).orElse(null);
        if (reminder == null) {
            log.warn("F03.11 リマインダーが読み直し時点で存在しません（スキップ）: reminderId={}", reminderId);
            return false;
        }
        if (reminder.getSentAt() != null) {
            // 既に送信済み。二重送信しない。
            return false;
        }

        RecruitmentListingEntity listing = listingRepository.findById(reminder.getListingId()).orElse(null);
        if (listing == null) {
            // 募集が削除済み → sent_at を確定してスキップ
            reminder.markSent(null);
            reminderRepository.save(reminder);
            return false;
        }

        RecruitmentParticipantEntity participant =
                participantRepository.findById(reminder.getParticipantId()).orElse(null);
        if (participant == null || participant.getUserId() == null) {
            // 参加者が削除済み or チーム参加 → sent_at を確定してスキップ
            reminder.markSent(null);
            reminderRepository.save(reminder);
            return false;
        }

        // sent_at の確定を先に行い、コミット後に通知を出す。逆順にすると確定の失敗で
        // 1 分後に同じ相手へ二重リマインドが飛ぶ（開催24時間前の通知は重複が実害となる）。
        // notification_id は非同期配送後にしか判明しないため null のまま（是正前と同じ）。
        reminder.markSent(null);
        reminderRepository.save(reminder);

        eventPublisher.publishEvent(new RecruitmentReminderNotificationEvent(
                reminder.getId(), listing.getId(), participant.getUserId()));
        return true;
    }
}
