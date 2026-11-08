package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import com.mannschaft.app.reservation.repository.ReservationPendingExpireScanStateRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/** 全runner共通の耐久進捗。unitの業務更新とはMANDATORYで同じTXに参加する。 */
@Service
@RequiredArgsConstructor
public class ReservationPendingExpireProgressService {

    private final ReservationPendingExpireScanStateRepository repository;
    private final Clock clock;
    private final ReservationRepository reservationRepository;
    private final EntityManager entityManager;

    public record RunState(long epoch, long highWater, long cursor, List<Long> retryPrimaryIds) { }

    /** 新周回の高水位だけ取得し、途中の周回へ新規流入を混ぜない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunState beginRun() {
        var state = locked();
        long highWater = state.getCycleHighWater();
        if (highWater == 0 && state.getLastInspectedId() == 0) {
            highWater = reservationRepository.findPendingExpireHighWater();
        }
        state.beginRun(highWater, clock.instant());
        return snapshot(state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunState beginRun(long highWater) {
        var state = locked();
        state.beginRun(highWater, clock.instant());
        return snapshot(state);
    }

    /** unit試行前の耐久投入。外側unit TXからは呼ばない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(long epoch, long primaryId) {
        owned(epoch).enqueue(primaryId, clock.instant());
    }

    /** unitの最初に呼ぶ。epoch確認後も呼出元unit TXがlockを保持する。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForUnit(long epoch) {
        owned(epoch);
    }

    /** 業務更新・通知DB行と同じunit TXに参加する。独立commitしない。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeUnit(long epoch, long primaryId, long completedPrefix) {
        var state = owned(epoch);
        state.removeRetry(primaryId, clock.instant());
        advancePrefix(state, completedPrefix);
    }

    /** rollback済みの失敗IDはretryへ残す。未処理trueを越えない位置を呼出元が渡す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpointFailure(long epoch, long primaryId, long completedPrefix) {
        var state = owned(epoch);
        if (!state.getRetryPrimaryIds().contains(primaryId)) {
            throw new IllegalStateException("失敗代表予約の耐久投入がありません");
        }
        advancePrefix(state, completedPrefix);
    }

    /** 実際に分類済みのfalse連続prefixだけを呼出元が渡す。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpoint(long epoch, long completedPrefix) {
        var state = owned(epoch);
        advancePrefix(state, completedPrefix);
    }

    /** 消滅/非PENDING/期限falseの旧retryを除き、fresh cursorは変更しない。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardRetry(long epoch, long primaryId) {
        owned(epoch).removeRetry(primaryId, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishCycle(long epoch) {
        owned(epoch).finishCycle(clock.instant());
    }

    private RunState snapshot(ReservationPendingExpireScanStateEntity state) {
        return new RunState(state.getRunEpoch(), state.getCycleHighWater(),
                state.getLastInspectedId(), state.getRetryPrimaryIds());
    }

    private void advancePrefix(ReservationPendingExpireScanStateEntity state, long completedPrefix) {
        if (completedPrefix < 0 || completedPrefix > state.getCycleHighWater()) {
            throw new IllegalArgumentException("検査済み位置は0から高水位までです");
        }
        state.advance(Math.max(state.getLastInspectedId(), completedPrefix), clock.instant());
    }

    private ReservationPendingExpireScanStateEntity locked() {
        var state = repository.lockSingleton().orElseThrow(
                () -> new IllegalStateException("仮押さえ失効の初期進捗行がありません"));
        // OSIV/L1の古いepoch/cursor/JSONで検証しない。root行だけを同TXでcurrent readする。
        entityManager.refresh(state, LockModeType.PESSIMISTIC_WRITE);
        state.validate();
        return state;
    }

    private ReservationPendingExpireScanStateEntity owned(long epoch) {
        var state = locked();
        if (epoch <= 0 || state.getRunEpoch() != epoch) {
            throw new IllegalStateException("仮押さえ失効の実行世代が変わりました");
        }
        return state;
    }
}
