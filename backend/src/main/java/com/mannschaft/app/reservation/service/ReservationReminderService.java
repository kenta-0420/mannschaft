package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.dto.CreateReminderRequest;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 予約リマインダーサービス。予約のリマインダー通知管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationReminderService {

    private static final int MAX_REMINDERS_PER_RESERVATION = 3;

    private final ReservationReminderRepository reminderRepository;
    private final ReservationMapper reservationMapper;

    /**
     * 予約のリマインダー一覧を取得する。
     *
     * @param reservationId 予約ID
     * @return リマインダーレスポンスリスト
     */
    public List<ReminderResponse> listReminders(Long reservationId) {
        List<ReservationReminderEntity> reminders =
                reminderRepository.findByReservationIdOrderByRemindAtAsc(reservationId);
        return reservationMapper.toReminderResponseList(reminders);
    }

    /**
     * リマインダーを作成する。
     *
     * @param reservationId 予約ID
     * @param request       作成リクエスト
     * @return 作成されたリマインダーレスポンス
     */
    @Transactional
    public ReminderResponse createReminder(Long reservationId, CreateReminderRequest request) {
        long count = reminderRepository.countByReservationId(reservationId);
        if (count >= MAX_REMINDERS_PER_RESERVATION) {
            throw new BusinessException(ReservationErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        ReservationReminderEntity entity = ReservationReminderEntity.builder()
                .reservationId(reservationId)
                .remindAt(request.getRemindAt())
                .build();

        ReservationReminderEntity saved = reminderRepository.save(entity);
        log.info("リマインダー作成: reservationId={}, remindAt={}", reservationId, request.getRemindAt());
        return reservationMapper.toReminderResponse(saved);
    }

    /**
     * 予約確定時のリマインダーを自動生成する（⑥ CONFIRMED 時リマインド自動生成）。
     *
     * <p>渡された {@code remindAtList} の各時刻についてリマインダーを生成する。
     * 1 予約あたりの上限（{@value #MAX_REMINDERS_PER_RESERVATION} 件）を超える分は生成しない。
     * 上限超過時に例外を投げず黙って打ち切るのは、本メソッドが確定 TX の AFTER_COMMIT 後に
     * 副作用として呼ばれる前提であり、リマインド過多で確定処理自体を失敗させないためである
     * （症状隠蔽ではなく、上限という仕様上の正常打ち切り。打ち切り件数はログに残す）。</p>
     *
     * <p>過去時刻のスキップ判定は呼び出し側（リスナー）が {@code Clock} を用いて行い、
     * 既に未来のみに絞り込んだリストを渡す責務とする。</p>
     *
     * @param reservationId 予約ID
     * @param remindAtList  生成するリマインド時刻リスト（未来時刻のみが渡される想定）
     * @return 実際に生成されたリマインダー件数
     */
    @Transactional
    public int generateReminders(Long reservationId, List<LocalDateTime> remindAtList) {
        long existing = reminderRepository.countByReservationId(reservationId);
        int created = 0;
        for (LocalDateTime remindAt : remindAtList) {
            if (existing + created >= MAX_REMINDERS_PER_RESERVATION) {
                log.info("リマインダー上限({})に達したため打ち切り: reservationId={}, 生成済み={}, 要求残={}",
                        MAX_REMINDERS_PER_RESERVATION, reservationId, created,
                        remindAtList.size() - created);
                break;
            }
            ReservationReminderEntity entity = ReservationReminderEntity.builder()
                    .reservationId(reservationId)
                    .remindAt(remindAt)
                    .build();
            reminderRepository.save(entity);
            created++;
        }
        if (created > 0) {
            log.info("予約確定リマインダー自動生成: reservationId={}, 生成件数={}", reservationId, created);
        }
        return created;
    }

    /**
     * リマインダーをキャンセルする。
     *
     * @param reminderId リマインダーID
     */
    @Transactional
    public void cancelReminder(Long reminderId) {
        ReservationReminderEntity entity = reminderRepository.findById(reminderId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.REMINDER_NOT_FOUND));
        entity.cancel();
        reminderRepository.save(entity);
        log.info("リマインダーキャンセル: reminderId={}", reminderId);
    }

    /**
     * 送信対象のリマインダーを取得し、送信済みにマークする。
     *
     * @return 送信対象のリマインダーリスト
     */
    @Transactional
    public List<ReservationReminderEntity> processPendingReminders() {
        List<ReservationReminderEntity> pending =
                reminderRepository.findByStatusAndRemindAtBefore(ReminderStatus.PENDING, LocalDateTime.now());

        for (ReservationReminderEntity reminder : pending) {
            reminder.markSent();
            reminderRepository.save(reminder);
        }

        if (!pending.isEmpty()) {
            log.info("リマインダー送信処理: {}件", pending.size());
        }
        return pending;
    }
}
