package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosurePreviewResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosureConfirmationResponse;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 臨時休業一括通知サービス。対象期間の予約を収集し、メール送信・キャンセル処理・履歴保存を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmergencyClosureService {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private static final String NOTIFICATION_TYPE = "EMERGENCY_CLOSURE";
    private static final String SOURCE_TYPE = "EMERGENCY_CLOSURE";

    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final NotificationHelper notificationHelper;
    private final EmergencyClosureRepository emergencyClosureRepository;
    private final ReservationSlotService slotService;
    private final EmergencyClosureConfirmationRepository confirmationRepository;

    /**
     * 臨時休業通知のプレビューを取得する。送信前に影響を受ける予約を確認するために使用する。
     *
     * @param teamId    チームID
     * @param startDate 休業開始日
     * @param endDate   休業終了日
     * @param startTime 部分時間帯休業の開始時刻（NULL なら終日）
     * @param endTime   部分時間帯休業の終了時刻（NULL なら終日）
     * @return プレビューレスポンス
     */
    public EmergencyClosurePreviewResponse previewClosure(
            Long teamId, LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime) {
        validateDateRange(startDate, endDate);
        validateTimeRange(startTime, endTime);

        List<ReservationEntity> activeReservations =
                findActiveReservations(teamId, startDate, endDate, startTime, endTime);
        List<EmergencyClosurePreviewResponse.AffectedReservation> affected =
                buildAffectedReservations(activeReservations);

        return EmergencyClosurePreviewResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .startTime(startTime)
                .endTime(endTime)
                .affectedCount(affected.size())
                .affectedReservations(affected)
                .build();
    }

    /**
     * 臨時休業通知を送信し、履歴を保存する。
     *
     * @param teamId          チームID
     * @param operatorUserId  操作者ユーザーID
     * @param request         通知リクエスト
     * @return 作成された臨時休業レスポンス
     */
    @Transactional
    public EmergencyClosureResponse sendClosure(Long teamId, Long operatorUserId, CreateEmergencyClosureRequest request) {
        validateDateRange(request.getStartDate(), request.getEndDate());
        validateTimeRange(request.getStartTime(), request.getEndTime());

        List<ReservationEntity> activeReservations =
                findActiveReservations(
                        teamId,
                        request.getStartDate(),
                        request.getEndDate(),
                        request.getStartTime(),
                        request.getEndTime());

        List<Long> targetUserIds = activeReservations.stream()
                .map(ReservationEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());

        // メール送信（ユーザーごとに1通）
        for (Long userId : targetUserIds) {
            userRepository.findById(userId).ifPresent(user ->
                emailService.sendEmail(user.getEmail(), request.getSubject(), request.getMessageBody())
            );
        }

        // 予約キャンセル処理
        if (request.isCancelReservations()) {
            for (ReservationEntity reservation : activeReservations) {
                reservation.cancel("臨時休業のためキャンセル", CancelledBy.ADMIN);
                reservationRepository.save(reservation);

                ReservationSlotEntity slot = slotService.getSlotEntity(reservation.getReservationSlotId());
                slotService.decrementAndReopen(slot);
            }
            log.info("臨時休業: 予約キャンセル完了 teamId={}, 件数={}", teamId, activeReservations.size());
        }

        // 履歴保存
        EmergencyClosureEntity entity = EmergencyClosureEntity.builder()
                .teamId(teamId)
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .subject(request.getSubject())
                .messageBody(request.getMessageBody())
                .sentCount(targetUserIds.size())
                .cancelReservations(request.isCancelReservations())
                .createdBy(operatorUserId)
                .build();

        EmergencyClosureEntity saved = emergencyClosureRepository.save(entity);
        log.info("臨時休業通知送信完了: teamId={}, closureId={}, sentCount={}", teamId, saved.getId(), targetUserIds.size());

        // アプリ内通知（WebSocket + PWA Push）— メールと二重で確実に届ける
        notificationHelper.notifyAll(
                targetUserIds,
                NOTIFICATION_TYPE,
                NotificationPriority.URGENT,
                request.getSubject(),
                request.getReason(),
                SOURCE_TYPE,
                saved.getId(),
                NotificationScopeType.TEAM,
                teamId,
                null,
                operatorUserId
        );

        // 確認追跡レコード作成
        Map<Long, ReservationSlotEntity> slotMap2 = slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, request.getStartDate(), request.getEndDate())
                .stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        for (Long userId : targetUserIds) {
            activeReservations.stream()
                    .filter(r -> r.getUserId().equals(userId))
                    .findFirst()
                    .ifPresent(earliest -> {
                        ReservationSlotEntity slot = slotMap2.get(earliest.getReservationSlotId());
                        if (slot != null) {
                            LocalDateTime appointmentAt =
                                    LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
                            confirmationRepository.save(
                                    EmergencyClosureConfirmationEntity.builder()
                                            .emergencyClosureId(saved.getId())
                                            .userId(userId)
                                            .reservationId(earliest.getId())
                                            .appointmentAt(appointmentAt)
                                            .build());
                        }
                    });
        }

        return toResponse(saved);
    }

    /**
     * チームの臨時休業履歴一覧を取得する。
     *
     * @param teamId チームID
     * @return 臨時休業レスポンスリスト
     */
    public List<EmergencyClosureResponse> listClosures(Long teamId) {
        return emergencyClosureRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 患者が臨時休業通知を確認済みとしてマークする。
     *
     * @param closureId 臨時休業ID
     * @param userId    確認したユーザーID
     */
    @Transactional
    public void confirmClosure(Long closureId, Long userId) {
        EmergencyClosureConfirmationEntity confirmation = confirmationRepository
                .findByEmergencyClosureIdAndUserId(closureId, userId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.CLOSURE_CONFIRMATION_NOT_FOUND));
        if (!confirmation.isConfirmed()) {
            confirmation.confirm();
            confirmationRepository.save(confirmation);
            log.info("臨時休業確認: closureId={}, userId={}", closureId, userId);
        }
    }

    /**
     * 臨時休業の確認状況一覧を取得する（送信者側）。
     *
     * @param teamId    チームID
     * @param closureId 臨時休業ID
     * @return 確認状況レスポンスリスト
     */
    public List<EmergencyClosureConfirmationResponse> getConfirmations(Long teamId, Long closureId) {
        emergencyClosureRepository.findById(closureId)
                .filter(c -> c.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.CLOSURE_NOT_FOUND));

        return confirmationRepository.findByEmergencyClosureId(closureId).stream()
                .map(c -> {
                    UserEntity user = userRepository.findById(c.getUserId()).orElse(null);
                    return EmergencyClosureConfirmationResponse.builder()
                            .userId(c.getUserId())
                            .userDisplayName(user != null ? user.getDisplayName() : "")
                            .userEmail(user != null ? user.getEmail() : "")
                            .appointmentAt(c.getAppointmentAt())
                            .confirmed(c.isConfirmed())
                            .confirmedAt(c.getConfirmedAt())
                            .reminderSent(c.getReminderSentAt() != null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ---- private helpers ----

    /**
     * 指定期間のチームスロットに紐付くアクティブ予約を収集する。
     *
     * <p>{@code startTime} / {@code endTime} が両方指定されている場合は、その時間帯と重複するスロットのみを対象にする。
     * 重複判定は {@code slot.startTime < endTime AND slot.endTime > startTime}（境界は含まない）。
     */
    private List<ReservationEntity> findActiveReservations(
            Long teamId, LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, startDate, endDate);

        if (slots.isEmpty()) {
            return List.of();
        }

        // 部分時間帯休業: 指定時間帯と重複するスロットのみに絞り込み
        if (startTime != null && endTime != null) {
            slots = slots.stream()
                    .filter(s -> s.getStartTime() != null && s.getEndTime() != null)
                    .filter(s -> s.getStartTime().isBefore(endTime) && s.getEndTime().isAfter(startTime))
                    .collect(Collectors.toList());

            if (slots.isEmpty()) {
                return List.of();
            }
        }

        List<Long> slotIds = slots.stream()
                .map(ReservationSlotEntity::getId)
                .collect(Collectors.toList());

        return reservationRepository.findByReservationSlotIdInAndStatusIn(slotIds, ACTIVE_STATUSES);
    }

    /**
     * 日付範囲のバリデーション。
     */
    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(ReservationErrorCode.INVALID_CLOSURE_DATE_RANGE);
        }
    }

    /**
     * 部分時間帯休業の時刻バリデーション。
     *
     * <ul>
     *   <li>両方 NULL → 終日休業として OK</li>
     *   <li>片方だけ指定 → エラー</li>
     *   <li>両方指定 → start < end かつ HH:00（分・秒・ナノ秒 = 0）でなければエラー</li>
     * </ul>
     */
    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if (startTime == null && endTime == null) {
            return;
        }
        if (startTime == null || endTime == null) {
            throw new BusinessException(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }
        if (!startTime.isBefore(endTime)) {
            throw new BusinessException(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }
        if (startTime.getMinute() != 0 || startTime.getSecond() != 0 || startTime.getNano() != 0
                || endTime.getMinute() != 0 || endTime.getSecond() != 0 || endTime.getNano() != 0) {
            throw new BusinessException(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }
    }

    /**
     * 予約リストから AffectedReservation リストを構築する。
     */
    private List<EmergencyClosurePreviewResponse.AffectedReservation> buildAffectedReservations(
            List<ReservationEntity> reservations) {

        if (reservations.isEmpty()) {
            return List.of();
        }

        // スロット情報をまとめて取得
        List<Long> slotIds = reservations.stream()
                .map(ReservationEntity::getReservationSlotId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, ReservationSlotEntity> slotMap = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));

        List<EmergencyClosurePreviewResponse.AffectedReservation> result = new ArrayList<>();
        for (ReservationEntity reservation : reservations) {
            ReservationSlotEntity slot = slotMap.get(reservation.getReservationSlotId());
            UserEntity user = userRepository.findById(reservation.getUserId()).orElse(null);

            String displayName = user != null ? user.getDisplayName() : "";
            String email = user != null ? user.getEmail() : "";

            EmergencyClosurePreviewResponse.AffectedReservation affected =
                    EmergencyClosurePreviewResponse.AffectedReservation.builder()
                            .reservationId(reservation.getId())
                            .userId(reservation.getUserId())
                            .userDisplayName(displayName)
                            .userEmail(email)
                            .slotDate(slot != null ? slot.getSlotDate() : null)
                            .startTime(slot != null ? slot.getStartTime() : null)
                            .endTime(slot != null ? slot.getEndTime() : null)
                            .status(reservation.getStatus().name())
                            .build();
            result.add(affected);
        }
        return result;
    }

    /**
     * EmergencyClosureEntity を EmergencyClosureResponse に変換する。
     */
    private EmergencyClosureResponse toResponse(EmergencyClosureEntity entity) {
        return EmergencyClosureResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .startTime(entity.getStartTime())
                .endTime(entity.getEndTime())
                .reason(entity.getReason())
                .subject(entity.getSubject())
                .messageBody(entity.getMessageBody())
                .sentCount(entity.getSentCount())
                .cancelReservations(entity.getCancelReservations())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
