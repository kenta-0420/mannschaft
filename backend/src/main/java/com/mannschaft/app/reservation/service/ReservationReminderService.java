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
