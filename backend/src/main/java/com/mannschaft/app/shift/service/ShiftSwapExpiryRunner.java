package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.event.ShiftSwapExpiredNotificationEvent;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * シフト交代申請の期限切れ自動キャンセルを 1 件ずつ実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット1。金型: {@code NotificationCreditResetRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code ShiftCleanupBatchService#runSwapExpiryCancel} に {@code @Transactional} が付き、
 * バッチ全体を 1 トランザクションで包んだままループ内で 1 件ずつ catch していた。1 件の失敗が
 * rollback-only を残すため、catch して続行した<b>他の申請のキャンセルもコミット時にまとめて消えていた</b>。
 * 楽観ロック競合を「スキップして継続」と扱っていた分岐も、実際には全件を巻き戻していた。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>再読込したうえで {@code PENDING} か再判定し、そうでなければ何もせず {@code false} を返す。
 * 抽出からここまでの間に成立・辞退で状態が変わっていた場合も、バッチ全体の再実行時も安全である。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftSwapExpiryRunner {

    private final ShiftSwapRequestRepository swapRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 件の交代申請を独立トランザクションでキャンセルし、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は 1 件も作られない。</p>
     *
     * @param swapId 交代申請ID
     * @return 実際にキャンセルした場合 {@code true}、対象外（既に PENDING でない・削除済み）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelOne(Long swapId) {
        ShiftSwapRequestEntity swap = swapRepository.findById(swapId).orElse(null);
        if (swap == null || swap.getStatus() != SwapRequestStatus.PENDING) {
            return false;
        }

        swap.cancel();
        swapRepository.save(swap);

        List<Long> recipients = new ArrayList<>();
        recipients.add(swap.getRequesterId());
        if (swap.getTargetUserId() != null) {
            recipients.add(swap.getTargetUserId());
        }
        eventPublisher.publishEvent(new ShiftSwapExpiredNotificationEvent(swap.getId(), recipients));
        return true;
    }
}
