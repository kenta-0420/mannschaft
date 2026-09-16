package com.mannschaft.app.payment.escrow.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.payment.escrow.EscrowLifecycleService;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済 第三陣: escrow ライフサイクル堅牢化バッチ（未確認放置の自動取消・設計書 02 §5.2 / §5.4）。
 *
 * <p>毎時実行し、以下を検出して自動取消＋通知する:</p>
 * <ul>
 *   <li>{@link EscrowStatus#PENDING_CONFIRMATION} で札主が confirm しないまま確認猶予
 *       （{@link #PENDING_CONFIRMATION_GRACE_HOURS}h・{@code created_at} 起点）を超過 → 与信取消＋札主/応じ手通知。</li>
 *   <li>{@link EscrowStatus#HELD}（受取口座未登録）で {@code hold_expires_at}（authorize 時 now+72h）が
 *       閾値（{@code now+}{@link #HOLD_EXPIRY_THRESHOLD_HOURS}h）以前 → 取消＋通知。</li>
 *   <li>{@link EscrowStatus#AUTHORIZED}（与信確定済）で {@code hold_expires_at}（最大7日）が閾値以前かつ
 *       未 capture → 取消＋通知（与信失効を放置しない・即時払い切替本体は後続陣）。</li>
 * </ul>
 *
 * <h3>確認猶予の基準と根拠</h3>
 * <p>PENDING_CONFIRMATION は札主の confirm 前ゆえ {@code hold_expires_at} が NULL（hold は confirm で初めて立つ・
 * 第一陣 status 意味論の根治）。よって確認放置は {@code created_at} 起点の経過時間で判定する。猶予は設計書 02 §5.2 の
 * 「72h 猶予（F13.1 §8.9 自動キャンセル相当）」に揃え {@link #PENDING_CONFIRMATION_GRACE_HOURS}=72h とする。
 * HELD/AUTHORIZED は {@code hold_expires_at} 起点で、§5.4 の「{@code hold_expires_at <= now()+2h}」に揃え
 * {@link #HOLD_EXPIRY_THRESHOLD_HOURS}=2h 前倒しで取り込む（hold 失効前に確実に処理する）。</p>
 *
 * <h3>冪等・行ロック・個別失敗の分離</h3>
 * <p>抽出（read）と実処理（write）を分離し、実処理は {@link EscrowLifecycleService} が
 * {@code PESSIMISTIC_WRITE} 行ロック＋status 再判定で 1 件ずつ {@code REQUIRES_NEW} の独立トランザクションで行う。
 * 1 件の失敗（Stripe 例外等）は個別 try/catch で ERROR ログに記録して握りつぶさず、他件の処理は継続する
 * （症状を隠さず観測可能化＝CLAUDE.md 根治原則）。多重起動は {@code @SchedulerLock} で防ぐ。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §5.2 / §5.4</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EscrowLifecycleBatch {

    /** 札主未 confirm 放置の確認猶予（72h・設計書 02 §5.2「72h 猶予」に整合）。 */
    static final long PENDING_CONFIRMATION_GRACE_HOURS = 72L;

    /** hold 失効の前倒し取り込み閾値（2h・設計書 02 §5.4「hold_expires_at <= now()+2h」に整合）。 */
    static final long HOLD_EXPIRY_THRESHOLD_HOURS = 2L;

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final EscrowLifecycleService escrowLifecycleService;
    private final Clock clock;

    /**
     * escrow ライフサイクルバッチ。毎時（fixedDelay=1h）実行する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとエスクローの期限到来・確定・返金の遷移が進まず、DB 上は確定なのに決済は未実行という乖離が残る")
    @BatchEndpoint(name = "escrow-lifecycle-hourly",
            description = "謝礼 escrow の未確認放置を自動取消（PENDING_CONFIRMATION 期限超過 / HELD・AUTHORIZED hold 失効）")
    @Scheduled(fixedDelay = 3_600_000)
    @SchedulerLock(name = "escrowLifecycleHourly", lockAtLeastFor = "PT5M", lockAtMostFor = "PT2H")
    public void run() {
        LocalDateTime now = LocalDateTime.now(clock);
        log.info("escrow ライフサイクルバッチ開始: now={}", now);

        int cancelledPending = cancelExpiredPendingConfirmations(now);
        int cancelledHeld = cancelExpiredByHoldExpiry(EscrowStatus.HELD, now);
        int cancelledAuthorized = cancelExpiredByHoldExpiry(EscrowStatus.AUTHORIZED, now);

        log.info("escrow ライフサイクルバッチ完了: pending取消={}, held取消={}, authorized取消={}",
                cancelledPending, cancelledHeld, cancelledAuthorized);
    }

    /**
     * PENDING_CONFIRMATION で created_at が猶予超過のものを取消する。
     *
     * <p>抽出（read）と実処理（{@link EscrowLifecycleService#cancelExpiredPendingConfirmation}＝
     * {@code REQUIRES_NEW} の独立トランザクション）を分離する。本メソッド自体はトランザクション境界を持たず、
     * 1 件ずつ独立 commit させて 1 件の失敗が他件のロールバックを巻き込まない（@Transactional は付けない＝
     * self-invocation でプロキシが効かないことに依存しない明示設計）。</p>
     */
    int cancelExpiredPendingConfirmations(LocalDateTime now) {
        LocalDateTime createdBefore = now.minusHours(PENDING_CONFIRMATION_GRACE_HOURS);
        List<EscrowTransactionEntity> targets = escrowTransactionRepository
                .findByStatusAndCreatedAtBefore(EscrowStatus.PENDING_CONFIRMATION, createdBefore);
        int count = 0;
        for (EscrowTransactionEntity e : targets) {
            UUID id = e.getId();
            try {
                if (escrowLifecycleService.cancelExpiredPendingConfirmation(id)) {
                    count++;
                }
            } catch (RuntimeException ex) {
                // 1 件の失敗は握りつぶさず ERROR ログに残し、他件の処理は継続する（観測可能化）。
                log.error("PENDING_CONFIRMATION 自動取消に失敗（他件は継続）: escrowId={}, reason={}",
                        id, ex.getMessage(), ex);
            }
        }
        return count;
    }

    /** HELD/AUTHORIZED で hold_expires_at が閾値以前のものを取消する（抽出と実処理を分離・個別独立 commit）。 */
    int cancelExpiredByHoldExpiry(EscrowStatus status, LocalDateTime now) {
        LocalDateTime threshold = now.plusHours(HOLD_EXPIRY_THRESHOLD_HOURS);
        List<EscrowTransactionEntity> targets = escrowTransactionRepository
                .findByStatusAndHoldExpiresAtLessThanEqual(status, threshold);
        int count = 0;
        for (EscrowTransactionEntity e : targets) {
            UUID id = e.getId();
            try {
                if (escrowLifecycleService.cancelExpiredHeldOrAuthorized(id)) {
                    count++;
                }
            } catch (RuntimeException ex) {
                log.error("{} 自動取消に失敗（他件は継続）: escrowId={}, reason={}",
                        status, id, ex.getMessage(), ex);
            }
        }
        return count;
    }
}
