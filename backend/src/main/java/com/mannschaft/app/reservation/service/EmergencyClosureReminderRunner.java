package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.event.EmergencyClosureReminderNotificationEvent;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 臨時休業未確認リマインドの「1 件ぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット1。金型: {@code NotificationCreditResetRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code EmergencyClosureReminderBatchService#processUnconfirmedReminders} に
 * {@code @Transactional} が付き、バッチ全体を 1 トランザクションで包んだままループ内で catch していた。
 * 1 件の DB 例外が rollback-only を残すため、catch して続行した<b>他の患者・送信者ぶんの
 * リマインド送信済み記録もコミット時にまとめて巻き戻り</b>、1 分後の再走査で全員に二重送信される
 * 状態だった（通知は既に送られているのに記録だけが消えるため）。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>抽出時点のスナップショットを信じず、独立トランザクション内で確認行を読み直し、
 * <b>まだ未確認であること</b>と<b>当該段階のリマインドが未送信であること</b>を再判定してから記録する。
 * 1 分間隔の起動・多重起動・再実行のいずれでも同じ段階を二度送らない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyClosureReminderRunner {

    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 件のリマインドを独立トランザクションで「送信済み」に記録し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない。</p>
     *
     * @param event 通知配送要求（{@code phase} で患者宛／送信者宛を切り替える）
     * @return 実際に記録した場合 {@code true}、対象外（既に確認済み・記録済み・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markReminderSent(EmergencyClosureReminderNotificationEvent event) {
        EmergencyClosureConfirmationEntity confirmation =
                confirmationRepository.findById(event.confirmationId()).orElse(null);
        if (confirmation == null) {
            return false;
        }
        if (confirmation.getConfirmedAt() != null) {
            // 抽出後に患者が確認した。リマインドは不要になっている。
            return false;
        }

        if (event.phase() == EmergencyClosureReminderNotificationEvent.Phase.PATIENT) {
            if (confirmation.getPatientReminderSentAt() != null) {
                return false;
            }
            confirmation.markPatientReminderSent();
        } else {
            if (confirmation.getReminderSentAt() != null) {
                return false;
            }
            confirmation.markReminderSent();
        }
        confirmationRepository.save(confirmation);

        eventPublisher.publishEvent(event);
        return true;
    }
}
