package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.CrossRefStatus;
import com.mannschaft.app.schedule.CrossRefTargetType;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.dto.CrossInviteRequest;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.CrossInviteEvent;
import com.mannschaft.app.schedule.repository.ScheduleCrossRefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * クロスチーム・組織招待管理サービス。スケジュールの招待送信・承認・拒否・キャンセルを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleCrossRefService {

    private static final String ACTION_SENT = "SENT";
    private static final String ACTION_ACCEPTED = "ACCEPTED";
    private static final String ACTION_REJECTED = "REJECTED";
    private static final String ACTION_CANCELLED = "CANCELLED";

    private final ScheduleCrossRefRepository crossRefRepository;
    private final ScheduleService scheduleService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * クロスチーム・組織招待を送信する。同じソース→ターゲットの招待が既にある場合はエラーとする。
     *
     * @param sourceScheduleId 招待元スケジュールID
     * @param req              招待リクエスト
     * @param userId           招待者ユーザーID
     * @return 招待レスポンス
     */
    @Transactional
    public CrossRefResponse sendCrossInvite(Long sourceScheduleId, CrossInviteRequest req, Long userId) {
        scheduleService.getSchedule(sourceScheduleId);
        CrossRefTargetType targetType = CrossRefTargetType.valueOf(req.getTargetType());

        // 重複チェック
        crossRefRepository.findBySourceScheduleIdAndTargetTypeAndTargetId(
                sourceScheduleId, targetType, req.getTargetId())
                .ifPresent(existing -> {
                    if (existing.getStatus() != CrossRefStatus.CANCELLED
                            && existing.getStatus() != CrossRefStatus.REJECTED) {
                        throw new BusinessException(ScheduleErrorCode.CROSS_INVITE_ALREADY_EXISTS);
                    }
                });

        ScheduleCrossRefEntity crossRef = ScheduleCrossRefEntity.builder()
                .sourceScheduleId(sourceScheduleId)
                .targetType(targetType)
                .targetId(req.getTargetId())
                .invitedBy(userId)
                .status(CrossRefStatus.PENDING)
                .message(req.getMessage())
                .build();

        crossRef = crossRefRepository.save(crossRef);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new CrossInviteEvent(
                sourceScheduleId, targetType.name(), req.getTargetId(), userId, ACTION_SENT));

        log.info("クロス招待送信: sourceScheduleId={}, targetType={}, targetId={}",
                sourceScheduleId, targetType, req.getTargetId());
        return toCrossRefResponse(crossRef);
    }

    /**
     * クロス招待をキャンセルする。
     *
     * @param invitationId 招待ID
     * @param userId       操作ユーザーID
     */
    @Transactional
    public void cancelCrossInvite(Long invitationId, Long userId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        validateInviteStatus(crossRef, CrossRefStatus.PENDING);

        crossRef.cancel();
        crossRefRepository.save(crossRef);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new CrossInviteEvent(
                crossRef.getSourceScheduleId(), crossRef.getTargetType().name(),
                crossRef.getTargetId(), userId, ACTION_CANCELLED));

        log.info("クロス招待キャンセル: invitationId={}", invitationId);
    }

    /**
     * 受信した招待一覧を取得する。PENDING ステータスの招待のみ返す。
     *
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @return 招待一覧
     */
    public List<CrossRefResponse> listReceivedInvitations(String targetType, Long targetId) {
        CrossRefTargetType type = CrossRefTargetType.valueOf(targetType);
        return crossRefRepository
                .findByTargetTypeAndTargetIdAndStatus(type, targetId, CrossRefStatus.PENDING)
                .stream()
                .map(this::toCrossRefResponse)
                .toList();
    }

    /**
     * 招待を承認する。招待元スケジュールを複製して招待先のスコープに作成する。
     *
     * @param invitationId 招待ID
     * @return 複製先スケジュールレスポンス
     */
    @Transactional
    public ScheduleResponse acceptInvitation(Long invitationId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        validateInviteStatus(crossRef, CrossRefStatus.PENDING);

        ScheduleEntity sourceSchedule = scheduleService.getSchedule(crossRef.getSourceScheduleId());

        // 招待元スケジュールを複製して招待先スコープに作成
        ScheduleEntity duplicate = sourceSchedule.toBuilder()
                .teamId(crossRef.getTargetType() == CrossRefTargetType.TEAM ? crossRef.getTargetId() : null)
                .organizationId(crossRef.getTargetType() == CrossRefTargetType.ORGANIZATION ? crossRef.getTargetId() : null)
                .userId(null)
                .parentScheduleId(null)
                .recurrenceRule(null)
                .googleCalendarEventId(null)
                .build();

        duplicate = scheduleService.findScheduleOrThrow(
                scheduleService.duplicateSchedule(crossRef.getSourceScheduleId(), crossRef.getInvitedBy()).getId());

        // 複製先IDを設定して承認
        crossRef.accept(duplicate.getId());
        crossRefRepository.save(crossRef);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new CrossInviteEvent(
                crossRef.getSourceScheduleId(), crossRef.getTargetType().name(),
                crossRef.getTargetId(), crossRef.getInvitedBy(), ACTION_ACCEPTED));

        log.info("クロス招待承認: invitationId={}, targetScheduleId={}", invitationId, duplicate.getId());

        return new ScheduleResponse(
                duplicate.getId(),
                duplicate.getTitle(),
                duplicate.getStartAt(),
                duplicate.getEndAt(),
                duplicate.getAllDay(),
                duplicate.getEventType().name(),
                duplicate.getStatus().name(),
                duplicate.getAttendanceRequired(),
                duplicate.getLocation(),
                duplicate.getCreatedAt());
    }

    /**
     * 招待を拒否する。
     *
     * @param invitationId 招待ID
     */
    @Transactional
    public void rejectInvitation(Long invitationId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        validateInviteStatus(crossRef, CrossRefStatus.PENDING);

        crossRef.reject();
        crossRefRepository.save(crossRef);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new CrossInviteEvent(
                crossRef.getSourceScheduleId(), crossRef.getTargetType().name(),
                crossRef.getTargetId(), crossRef.getInvitedBy(), ACTION_REJECTED));

        log.info("クロス招待拒否: invitationId={}", invitationId);
    }

    /**
     * 招待の最終確認を行う（非公開チーム用）。AWAITING_CONFIRMATION → ACCEPTED に遷移する。
     *
     * @param invitationId 招待ID
     */
    @Transactional
    public void confirmInvitation(Long invitationId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        validateInviteStatus(crossRef, CrossRefStatus.AWAITING_CONFIRMATION);

        crossRef.accept(crossRef.getTargetScheduleId());
        crossRefRepository.save(crossRef);

        log.info("クロス招待確認完了: invitationId={}", invitationId);
    }

    // --- プライベートメソッド ---

    /**
     * クロスリファレンスを取得する。存在しない場合は例外をスローする。
     */
    private ScheduleCrossRefEntity findCrossRefOrThrow(Long id) {
        return crossRefRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.CROSS_INVITE_NOT_FOUND));
    }

    /**
     * 招待の現在ステータスが期待するステータスかどうかを検証する。
     */
    private void validateInviteStatus(ScheduleCrossRefEntity crossRef, CrossRefStatus expected) {
        if (crossRef.getStatus() != expected) {
            throw new BusinessException(ScheduleErrorCode.CROSS_INVITE_INVALID_STATUS);
        }
    }

    /**
     * エンティティをクロスリファレンスレスポンスDTOに変換する。
     */
    private CrossRefResponse toCrossRefResponse(ScheduleCrossRefEntity entity) {
        return new CrossRefResponse(
                entity.getId(),
                entity.getSourceScheduleId(),
                entity.getTargetType().name(),
                entity.getTargetId(),
                entity.getTargetScheduleId(),
                entity.getStatus().name(),
                entity.getMessage(),
                entity.getInvitedBy(),
                entity.getCreatedAt(),
                entity.getRespondedAt());
    }
}
