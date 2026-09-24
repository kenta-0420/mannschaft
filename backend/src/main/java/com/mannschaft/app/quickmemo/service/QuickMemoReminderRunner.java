package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.event.QuickMemoReminderNotificationEvent;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ポイっとメモ リマインドの「1 ユーザーぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット1。金型: {@code NotificationCreditResetRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code QuickMemoReminderBatchService#execute} に {@code @Transactional} が付き、
 * バッチ全体を 1 トランザクションで包んだままユーザー単位に catch していた。1 ユーザーぶんの
 * DB 例外が rollback-only を残すため、catch して続行した<b>他ユーザーの送信済み記録も
 * コミット時にまとめて巻き戻り</b>、次回起動で全員に二重通知が飛びうる状態だった。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>抽出時点のスナップショットを信じず、独立トランザクション内で<b>メモを読み直して
 * {@code reminderXSentAt IS NULL} を再判定</b>してから記録する。二重起動・再実行時に
 * 同じ枠を二度「送信済み」にすることはない。1 枠も記録できなかった場合は
 * 通知配送要求を publish せず {@code 0} を返す（空通知を出さない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickMemoReminderRunner {

    private final QuickMemoRepository memoRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 ユーザーぶんのリマインド枠を独立トランザクションで送信済みに記録し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない（＝送信済み記録だけが残ることも、
     * 記録なしに通知だけ飛ぶこともない）。</p>
     *
     * @param userId  受信者ユーザーID
     * @param memoIds 抽出時点でリマインド対象だったメモのID
     * @param now     判定基準時刻（JST。{@code reminderXScheduledAt} の保存基準と同じ）
     * @return 実際にリマインド対象として記録したメモの件数。0 なら通知は publish されない
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markRemindersSent(Long userId, List<Long> memoIds, LocalDateTime now) {
        // 抽出後にメモが編集・削除されている可能性があるため読み直す（冪等性の要）。
        List<QuickMemoEntity> memos = memoRepository.findAllById(memoIds);

        List<Long> slot1 = new ArrayList<>();
        List<Long> slot2 = new ArrayList<>();
        List<Long> slot3 = new ArrayList<>();
        int reminded = 0;
        for (QuickMemoEntity memo : memos) {
            if (!userId.equals(memo.getUserId())) {
                // 抽出とグルーピングの前提が崩れている（本来起こらない）。他人のメモを触らない。
                log.warn("リマインド対象メモの所有者が一致しません（スキップ）: memoId={}, expectedUserId={}",
                        memo.getId(), userId);
                continue;
            }
            boolean any = false;
            if (isDue(memo.getReminder1ScheduledAt(), memo.getReminder1SentAt(), now)) {
                slot1.add(memo.getId());
                any = true;
            }
            if (isDue(memo.getReminder2ScheduledAt(), memo.getReminder2SentAt(), now)) {
                slot2.add(memo.getId());
                any = true;
            }
            if (isDue(memo.getReminder3ScheduledAt(), memo.getReminder3SentAt(), now)) {
                slot3.add(memo.getId());
                any = true;
            }
            if (any) {
                reminded++;
            }
        }

        if (reminded == 0) {
            return 0;
        }

        // markReminderXSent は clearAutomatically=true で永続化コンテキストを掃除するため、
        // 上のループで必要な値を全て読み終えてから発行する。
        slot1.forEach(id -> memoRepository.markReminder1Sent(id, now));
        slot2.forEach(id -> memoRepository.markReminder2Sent(id, now));
        slot3.forEach(id -> memoRepository.markReminder3Sent(id, now));

        eventPublisher.publishEvent(new QuickMemoReminderNotificationEvent(userId, reminded));
        return reminded;
    }

    /** 予定時刻を過ぎており、かつ未送信の枠かを判定する（是正前の分岐をそのまま移送）。 */
    private boolean isDue(LocalDateTime scheduledAt, LocalDateTime sentAt, LocalDateTime now) {
        return scheduledAt != null && !scheduledAt.isAfter(now) && sentAt == null;
    }
}
