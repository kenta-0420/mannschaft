package com.mannschaft.app.payment.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.UserConstants;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 30 日後物理削除（{@link AccountPurgedEvent}）を購読し、
 * payment ドメインの 2 種類の越境処理を実行する:
 * <ol>
 *   <li>{@code member_payments.user_id} を {@link UserConstants#SENTINEL_USER_ID} に
 *       差し替える（センチネル化）— 支払い履歴は会計税法 7 年保持要件のため保存する</li>
 *   <li>{@code stripe_customers} 行を物理削除する（外部 Stripe 顧客との紐付け解除）</li>
 * </ol>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>GDPR と会計税法の両立:</b>
 * GDPR Art. 17（削除権）は個人特定情報の消去を要求するが、日本の会計税法は支払い記録の
 * 7 年保持を要求する。本リスナーはこの矛盾を「センチネル差替」（user_id を非個人 ID に
 * 置換）で解決し、支払い金額・日付・項目との紐付け統計を温存しつつ個人特定性のみ消す。
 * Stripe 顧客は外部システム上の個人連携データのため物理削除する（Stripe API 側の
 * 顧客レコード自体は本 PR スコープ外、運用または別バッチで処理）。</p>
 *
 * <p><b>センチネル差替の冪等性ノート（親設計書 §4 Phase B-3）:</b>
 * {@code MemberPaymentRepository#anonymizeUserId(userId, SENTINEL)} は
 * {@code WHERE user_id = :userId} のターゲット選択型 UPDATE である。
 * 1 回目: 該当行が SENTINEL に差替される。
 * 2 回目以降: もはや user_id = :userId に該当する行は存在せず、対象 0 件で正常終了する。
 * これは「2 回呼んでも有害ではない」という意味で安全であるが、厳密な冪等（同じ結果セットが
 * 返る）ではない once-only ターゲット選択型である点に留意。Phase B 併走中は
 * 「listener が先に anonymize → 後で {@code AccountPurgeService} の越境 DML が改めて
 * anonymizeUserId を呼ぶ」順序で、2 回目は 0 件処理になることを前提とする。</p>
 *
 * <p><b>既存越境 DML との関係:</b>
 * 現状 {@code AccountPurgeService#purgeUser}（gdpr/auth ドメイン）は同じセンチネル化 DML と
 * Stripe 顧客削除を直接呼んでいる（{@code AccountPurgeService.java:177-183}）。
 * 本リスナーが導入されることで Phase B 併走期間中は二重実行になるが、
 * 「センチネル化は 2 回目以降 0 件で無害」「Stripe 顧客削除は 2 回目以降 {@code findByUserId}
 * が空で {@code delete} 未呼出」のため機能影響なし。既存越境 DML は Phase C-2 で撤去予定。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B-3 / §3.1 / §3.4 / 親設計書 §4 Phase B-3 ノート（冪等性留意）/
 * PR #837 (Phase B-1 role) / PR #845 (Phase B-2 team) と同型の三重防御パターン。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentPurgeEventListener {

    private final MemberPaymentRepository memberPaymentRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーに紐付く payment ドメイン行を
     * GDPR Art. 17 に従って処理する（センチネル差替 + Stripe 顧客行物理削除）。
     *
     * <p>2 操作はそれぞれ独立した try-catch で囲み、1 操作が失敗しても他の操作は
     * 継続実行する（GDPR 30 日タイムリミット遵守のため）。失敗件は WARN ログを残し、
     * 夜次補正バッチ（Phase D で導入予定）で再処理する運用とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会確定者の支払履歴のセンチネル化と stripe_customers 行の削除が実行されず、GDPR 第17条の消去期限を直接破り外部 Stripe 顧客との紐付けも残る")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        int sentinelizedPayments = 0;
        boolean sentinelizeFailed = false;
        boolean stripeDeleted = false;
        boolean stripeFailed = false;

        // 操作 1: member_payments.user_id をセンチネル ID（0）に差替（会計税法 7 年保持と GDPR の両立）
        try {
            sentinelizedPayments = memberPaymentRepository.anonymizeUserId(
                    userId, UserConstants.SENTINEL_USER_ID);
        } catch (Exception e) {
            sentinelizeFailed = true;
            log.warn("ユーザー退会 payment purge: member_payments センチネル化失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        // 操作 2: stripe_customers 行を物理削除（外部 Stripe 顧客との紐付け解除）
        // Stripe API 側の顧客レコード自体の削除は本 PR スコープ外（運用または別バッチで処理）
        try {
            var stripeCustomerOpt = stripeCustomerRepository.findByUserId(userId);
            if (stripeCustomerOpt.isPresent()) {
                stripeCustomerRepository.delete(stripeCustomerOpt.get());
                stripeDeleted = true;
            }
        } catch (Exception e) {
            stripeFailed = true;
            log.warn("ユーザー退会 payment purge: stripe_customers 削除失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        log.info(
                "ユーザー退会 payment purge 完了: userId={}, sentinelizedPayments={}, stripeDeleted={}, sentinelizeFailed={}, stripeFailed={}",
                userId, sentinelizedPayments, stripeDeleted, sentinelizeFailed, stripeFailed);

        // Phase D-8: 処理完了を completion_status に記録（両操作とも失敗なしの場合のみ SUCCESS とする）
        if (!sentinelizeFailed && !stripeFailed) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "payment")
                    .ifPresent(entity -> {
                        entity.setStatus("SUCCESS");
                        entity.setCompletedAt(LocalDateTime.now());
                        completionStatusRepository.save(entity);
                    });
        }
    }

    /**
     * 管理者からの手動 retry 用。{@link #on(AccountPurgedEvent)} と同じドメイン操作を実行するが、
     * {@code completionStatusRepository} の更新は {@code GdprPurgeRetryService} が担う。
     *
     * @param userId retry 対象ユーザー ID
     * @return true=全操作成功、false=1 件以上失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        boolean sentinelizeFailed = false;
        boolean stripeFailed = false;

        try {
            memberPaymentRepository.anonymizeUserId(userId, UserConstants.SENTINEL_USER_ID);
        } catch (Exception e) {
            sentinelizeFailed = true;
            log.warn("payment purge retry: member_payments センチネル化失敗 userId={}", userId, e);
        }

        try {
            var stripeCustomerOpt = stripeCustomerRepository.findByUserId(userId);
            if (stripeCustomerOpt.isPresent()) {
                stripeCustomerRepository.delete(stripeCustomerOpt.get());
            }
        } catch (Exception e) {
            stripeFailed = true;
            log.warn("payment purge retry: stripe_customers 削除失敗 userId={}", userId, e);
        }

        return !sentinelizeFailed && !stripeFailed;
    }
}
