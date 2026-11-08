package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
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
import com.mannschaft.app.reservation.EmergencyClosureConfirmedEvent;
import com.mannschaft.app.reservation.event.EmergencyClosureNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    /** 氏名・メールの取得は auth ドメインの Service 経由（D-5: 他ドメインの Repository / Entity を直接使わない）。 */
    private final UserService userService;
    private final EmailOutboxService emailOutboxService;
    private final EmergencyClosureRepository emergencyClosureRepository;
    private final ReservationSlotService slotService;
    private final EmergencyClosureConfirmationRepository confirmationRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        // CMP-261104-0334: 冪等キー付きの送信は EmergencyClosureSendService が IdempotentEmergencyClosureRequest で渡す。
        // キーとハッシュを休業行に保存し、同じ (チーム, 送信者, キー) の行が既にあれば休業行の INSERT が一意制約
        // EmergencyClosureEntity#IDEMPOTENCY_UNIQUE_CONSTRAINT で失敗して本 TX（予約取消・枠減算・outbox・確認行を含む）は
        // 丸ごと巻き戻る（通知配送は AFTER_COMMIT のため発火しない）。重複の判別と既存結果の返却は TX の外の
        // EmergencyClosureSendService が行う（本メソッドは別 Bean からプロキシ越しに呼ばれる。自己呼び出しでは
        // TX 境界の外で例外を捕捉できない）。引数で渡さず型で渡すのは、本メソッドのシグネチャを変えずに
        // 既存の D-3T 凍結キー（推移的クロスドメイン TX の既存負債）を据え置くため。
        String idempotencyKey = null;
        String requestHash = null;
        if (request instanceof IdempotentEmergencyClosureRequest idempotent) {
            idempotencyKey = idempotent.getIdempotencyKey();
            requestHash = idempotent.getRequestHash();
        }
        validateDateRange(request.getStartDate(), request.getEndDate());
        validateTimeRange(request.getStartTime(), request.getEndTime());

        // CMP-260822-1730 AC-1730-6 / 検分 r2 P1: 一括取消するときは対象行を id 昇順で一括ロックし、
        // ロック後の最新状態で active なものだけを取消・枠減算する。非ロック読取のままだと、読取後に
        // 仮押さえ失効バッチが同じ PENDING を取消・枠減算して commit したとき、こちらも同じ予約で
        // 枠を再減算し（二重減算＝過剰予約）、失効済みの利用者へ休業通知まで重ねて送る。
        List<ReservationEntity> activeReservations = request.isCancelReservations()
                ? lockActiveReservations(
                        teamId,
                        request.getStartDate(),
                        request.getEndDate(),
                        request.getStartTime(),
                        request.getEndTime())
                : findActiveReservations(
                        teamId,
                        request.getStartDate(),
                        request.getEndDate(),
                        request.getStartTime(),
                        request.getEndTime());

        List<Long> targetUserIds = activeReservations.stream()
                .map(ReservationEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());

        // 予約キャンセル処理
        if (request.isCancelReservations()) {
            List<ReservationSlotEntity> slotsToReopen = new java.util.ArrayList<>();
            for (ReservationEntity reservation : activeReservations) {
                reservation.cancel("臨時休業のためキャンセル", CancelledBy.ADMIN);
                reservationRepository.save(reservation);
                slotsToReopen.add(slotService.getSlotEntity(reservation.getReservationSlotId()));
            }
            // 枠 ID 昇順で減算する（AC-1730-6 / 検分 r3 P2・失効バッチと枠ロックの取得順を揃える）。
            SlotLockOrder.byIdAsc(slotsToReopen).forEach(slotService::decrementAndReopen);
            log.info("臨時休業: 予約キャンセル完了 teamId={}, 件数={}", teamId, activeReservations.size());
        }

        // 履歴保存（closure.id を確定させてからメール enqueue に進む。
        // sourceEventId = "emergency-closure:" + closure.id + ":" + userId として一意化する）
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
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .build();

        EmergencyClosureEntity saved = emergencyClosureRepository.save(entity);
        log.info("臨時休業通知保存完了: teamId={}, closureId={}, sentCount={}", teamId, saved.getId(), targetUserIds.size());

        // F09.18 Phase 18-b: メール送信は EmailOutboxService.enqueue() 経由に移行
        // (1 record = 1 宛先展開、テンプレ未整備のため RESERVATION_EMERGENCY_CLOSURE はスルー方式)
        Map<Long, UserService.FullNameAndEmail> contacts = userService.getFullNamesAndEmails(targetUserIds);
        for (Long userId : targetUserIds) {
            java.util.Optional.ofNullable(contacts.get(userId)).ifPresent(user ->
                emailOutboxService.enqueue(new EmailOutboxRequest(
                        "RESERVATION_EMERGENCY_CLOSURE",
                        "ja",
                        user.email(),
                        Map.of(
                                "subject", request.getSubject(),
                                "body", request.getMessageBody()
                        ),
                        "reservation",
                        "emergency-closure:" + saved.getId() + ":" + userId,
                        null,
                        userId,
                        null  // EmergencyClosureEntity に organizationId フィールドが無いため null
                ))
            );
        }

        // アプリ内通知（WebSocket + PWA Push）— メールと二重で確実に届ける。
        // 業務TX内では配送要求の publish だけを行い、実配送は EmergencyClosureNotificationListener が
        // AFTER_COMMIT で行う（Issue #2997 G3: 通知の失敗で休業・キャンセル・確認行を巻き戻さない）。
        if (!targetUserIds.isEmpty()) {
            eventPublisher.publishEvent(new EmergencyClosureNotificationEvent(saved.getId(), targetUserIds));
        }

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
     * 冪等キーで送信済みの臨時休業と、その要求内容のハッシュを取得する（CMP-261104-0334）。
     *
     * <p>読み取り専用の新しい TX で読むため、同時送信で先着が commit した行も見える。</p>
     *
     * @param teamId         チームID
     * @param operatorUserId 送信者ユーザーID
     * @param idempotencyKey 正規化済みの冪等キー
     * @return 送信済みなら結果とハッシュ
     */
    public Optional<IdempotentClosure> findIdempotentClosure(Long teamId, Long operatorUserId, String idempotencyKey) {
        return emergencyClosureRepository
                .findByTeamIdAndCreatedByAndIdempotencyKey(teamId, operatorUserId, idempotencyKey)
                .map(entity -> new IdempotentClosure(toResponse(entity), entity.getRequestHash()));
    }

    /**
     * 冪等キーで送信済みの臨時休業の結果と要求内容のハッシュ。
     *
     * @param response    臨時休業レスポンス
     * @param requestHash 先に受け付けた要求内容の SHA-256
     */
    public record IdempotentClosure(EmergencyClosureResponse response, String requestHash) {
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

            // 確認成功（confirmed=true 保存）直後にイベント発火。
            // 配信先トピックのスコープに teamId が必要なため closure から取得する（無ければ配信スキップ）。
            // 受信は EmergencyClosureBroadcastListener（AFTER_COMMIT・配信専用）。
            emergencyClosureRepository.findById(closureId).ifPresent(closure ->
                    eventPublisher.publishEvent(EmergencyClosureConfirmedEvent.builder()
                            .closureId(closureId)
                            .teamId(closure.getTeamId())
                            .userId(userId)
                            .confirmedAt(confirmation.getConfirmedAt())
                            .build()));
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

        List<EmergencyClosureConfirmationEntity> confirmations =
                confirmationRepository.findByEmergencyClosureId(closureId);
        // N+1 回避: 対象ユーザーを1回で取得する（退会者は @SQLRestriction で取れず、氏名・メールは "" で返す）
        List<Long> userIds = confirmations.stream()
                .map(EmergencyClosureConfirmationEntity::getUserId).distinct().collect(Collectors.toList());
        Map<Long, UserService.FullNameAndEmail> userMap =
                userIds.isEmpty() ? Map.of() : userService.getFullNamesAndEmails(userIds);

        return confirmations.stream()
                .map(c -> {
                    UserService.FullNameAndEmail user = userMap.get(c.getUserId());
                    return EmergencyClosureConfirmationResponse.builder()
                            .userId(c.getUserId())
                            .userFullName(user != null ? user.fullName() : "")
                            .userEmail(user != null ? user.email() : "")
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
        List<Long> slotIds = findTargetSlotIds(teamId, startDate, endDate, startTime, endTime);
        if (slotIds.isEmpty()) {
            return List.of();
        }
        return reservationRepository.findByReservationSlotIdInAndStatusIn(slotIds, ACTIVE_STATUSES);
    }

    /**
     * 一括取消の対象予約を行ロック付きで取得する（CMP-260822-1730 AC-1730-6）。
     *
     * <p>対象 id 集合をスカラー問い合わせで確定してから {@code findByIdInForUpdate} で id 昇順に一括ロックする。
     * エンティティを非ロックで先読みしないのは、永続化コンテキストに古い状態が残り行ロック取得後も
     * 最新状態を読めなくなるため。ロック待ちの間に失効バッチ等が終端状態へ遷移させた行は、
     * ロック後に読む最新状態で除外する（二重取消・枠の二重減算・通知の重複を作らない）。</p>
     */
    private List<ReservationEntity> lockActiveReservations(
            Long teamId, LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime) {
        List<Long> slotIds = findTargetSlotIds(teamId, startDate, endDate, startTime, endTime);
        if (slotIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = reservationRepository.findIdsByReservationSlotIdInAndStatusIn(slotIds, ACTIVE_STATUSES);
        if (ids.isEmpty()) {
            return List.of();
        }
        return reservationRepository.findByIdInForUpdate(ids).stream()
                .filter(r -> ACTIVE_STATUSES.contains(r.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * 休業期間（部分時間帯休業なら時間帯 overlap も）に該当するチーム枠の ID を列挙する。
     */
    private List<Long> findTargetSlotIds(
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

        return slots.stream()
                .map(ReservationSlotEntity::getId)
                .collect(Collectors.toList());
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

        // N+1 回避: 対象ユーザーを1回で取得する（見つからないユーザーは氏名・メール "" のまま行を残す）
        List<Long> userIds = reservations.stream()
                .map(ReservationEntity::getUserId).distinct().collect(Collectors.toList());
        Map<Long, UserService.FullNameAndEmail> userMap = userService.getFullNamesAndEmails(userIds);

        List<EmergencyClosurePreviewResponse.AffectedReservation> result = new ArrayList<>();
        for (ReservationEntity reservation : reservations) {
            ReservationSlotEntity slot = slotMap.get(reservation.getReservationSlotId());
            UserService.FullNameAndEmail user = userMap.get(reservation.getUserId());

            String fullName = user != null ? user.fullName() : "";
            String email = user != null ? user.email() : "";

            EmergencyClosurePreviewResponse.AffectedReservation affected =
                    EmergencyClosurePreviewResponse.AffectedReservation.builder()
                            .reservationId(reservation.getId())
                            .userId(reservation.getUserId())
                            .userFullName(fullName)
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
