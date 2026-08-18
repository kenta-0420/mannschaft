package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
     * 認可根治 Wave6: 招待の受信側スコープ（entity 由来 targetType/targetId）に対する
     * per-scope 認可に使用する。
     */
    private final AccessControlService accessControlService;

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
        // 認可根治 Wave6: 招待元スケジュールの entity 由来 scope の ADMIN/DEPUTY_ADMIN のみ送信可。
        checkSourceScheduleAdmin(sourceScheduleId, userId);
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
        // 認可根治 Wave6: 送信側（招待元スケジュールの entity 由来 scope）の ADMIN/DEPUTY_ADMIN のみ取消可。
        checkSourceScheduleAdmin(crossRef.getSourceScheduleId(), userId);
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
     * <p><b>認可（認可根治 Wave6）</b>: 受信側スコープ（TEAM/ORGANIZATION）のメンバーのみ閲覧可
     * （{@code checkMembership} 水準）。</p>
     *
     * @param targetType ターゲット種別
     * @param targetId   ターゲットID
     * @param userId     閲覧ユーザーID
     * @return 招待一覧
     */
    public List<CrossRefResponse> listReceivedInvitations(String targetType, Long targetId, Long userId) {
        CrossRefTargetType type = CrossRefTargetType.valueOf(targetType);
        if (!accessControlService.isSystemAdmin(userId)) {
            // CrossRefTargetType（TEAM/ORGANIZATION）は AccessControlService の scopeType と同名。
            accessControlService.checkMembership(userId, targetId, type.name());
        }
        return crossRefRepository
                .findByTargetTypeAndTargetIdAndStatus(type, targetId, CrossRefStatus.PENDING)
                .stream()
                .map(this::toCrossRefResponse)
                .toList();
    }

    /**
     * 招待を承認する。招待元スケジュールを複製して招待先のスコープに作成する。
     *
     * <p><b>認可（認可根治 Wave6）</b>: 受信側スコープの ADMIN/DEPUTY_ADMIN のみ承認可。
     * URL の scope と招待 entity の target が一致することも併せて検証する。</p>
     *
     * @param targetType   URL 由来のターゲット種別
     * @param targetId     URL 由来のターゲットID
     * @param invitationId 招待ID
     * @param userId       操作ユーザーID
     * @return 複製先スケジュールレスポンス
     */
    @Transactional
    public ScheduleResponse acceptInvitation(String targetType, Long targetId,
                                             Long invitationId, Long userId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        checkTargetScopeAdmin(crossRef, targetType, targetId, userId);
        validateInviteStatus(crossRef, CrossRefStatus.PENDING);

        // 招待元スケジュールを複製して招待先スコープに作成
        ScheduleEntity duplicate = scheduleService.duplicateScheduleIntoScope(
                crossRef.getSourceScheduleId(), crossRef.getTargetType().name(),
                crossRef.getTargetId(), userId);

        // 複製先IDを設定して承認
        crossRef.accept(duplicate.getId());
        crossRefRepository.save(crossRef);

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new CrossInviteEvent(
                crossRef.getSourceScheduleId(), crossRef.getTargetType().name(),
                crossRef.getTargetId(), crossRef.getInvitedBy(), ACTION_ACCEPTED));

        log.info("クロス招待承認: invitationId={}, targetScheduleId={}", invitationId, duplicate.getId());

        return ScheduleResponse.builder()
                .id(duplicate.getId())
                .content(new ScheduleResponse.ScheduleContentDto(
                        duplicate.getTitle(),
                        duplicate.getStatus().name(),
                        duplicate.getEventType().name(),
                        duplicate.getLocation(),
                        duplicate.getAttendanceRequired()))
                .time(new ScheduleResponse.ScheduleTimeDto(
                        duplicate.getStartAt(), duplicate.getEndAt(), duplicate.getAllDay()))
                .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                .academic(new ScheduleResponse.ScheduleAcademicDto(
                        null,
                        duplicate.getAcademicYear() != null ? duplicate.getAcademicYear().intValue() : null,
                        duplicate.getSourceScheduleId()))
                .audit(new ScheduleResponse.ScheduleAuditDto(duplicate.getCreatedAt(), null))
                .myAttendanceStatus(null)
                .build();
    }

    /**
     * 招待を拒否する。
     *
     * <p><b>認可（認可根治 Wave6）</b>: {@link #acceptInvitation} と同水準。</p>
     *
     * @param targetType   URL 由来のターゲット種別
     * @param targetId     URL 由来のターゲットID
     * @param invitationId 招待ID
     * @param userId       操作ユーザーID
     */
    @Transactional
    public void rejectInvitation(String targetType, Long targetId, Long invitationId, Long userId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        checkTargetScopeAdmin(crossRef, targetType, targetId, userId);
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
     * <p><b>認可（認可根治 Wave6）</b>: {@link #acceptInvitation} と同水準。</p>
     *
     * @param targetType   URL 由来のターゲット種別
     * @param targetId     URL 由来のターゲットID
     * @param invitationId 招待ID
     * @param userId       操作ユーザーID
     */
    @Transactional
    public void confirmInvitation(String targetType, Long targetId, Long invitationId, Long userId) {
        ScheduleCrossRefEntity crossRef = findCrossRefOrThrow(invitationId);
        checkTargetScopeAdmin(crossRef, targetType, targetId, userId);
        validateInviteStatus(crossRef, CrossRefStatus.AWAITING_CONFIRMATION);

        crossRef.accept(crossRef.getTargetScheduleId());
        crossRefRepository.save(crossRef);

        log.info("クロス招待確認完了: invitationId={}", invitationId);
    }

    // --- プライベートメソッド ---

    /**
     * 招待の送信側（招待元スケジュールの entity 由来 scope）に対する ADMIN 認可を強制する
     * （認可根治 Wave6）。
     *
     * <p>scope 解決は同ドメインの正準である {@link ScheduleService#checkScopeAdminAccess(Long, Long)}
     * に委譲する（独自実装をしない）。SYSTEM_ADMIN の横断許可だけは本メソッドで先に短絡させ、
     * 委譲先と同じ判定を重ねて引かないようにする（{@code checkScopeViewAccess} と同方針）。</p>
     */
    private void checkSourceScheduleAdmin(Long sourceScheduleId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        scheduleService.checkScopeAdminAccess(sourceScheduleId, userId);
    }

    /**
     * 招待の受信側スコープに対する ADMIN 認可を強制する（認可根治 Wave6）。
     *
     * <p>path 由来の scope を鵜呑みにせず、まず招待 entity の {@code targetType}/{@code targetId} が
     * URL の scope と一致することを確認し（BOLA 封鎖）、そのうえで entity 由来 scope に対して
     * {@link AccessControlService#checkAdminOrAbove} を適用する。SYSTEM_ADMIN は横断で許可
     * （{@code ScheduleService.checkScopeViewAccess} と同方針）。</p>
     */
    private void checkTargetScopeAdmin(ScheduleCrossRefEntity crossRef,
                                       String targetType, Long targetId, Long userId) {
        if (crossRef.getTargetType() != CrossRefTargetType.valueOf(targetType)
                || !java.util.Objects.equals(crossRef.getTargetId(), targetId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, crossRef.getTargetId(),
                crossRef.getTargetType().name());
    }

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
        return CrossRefResponse.builder()
                .id(entity.getId())
                .sourceScheduleId(entity.getSourceScheduleId())
                .target(new CrossRefResponse.CrossRefTargetDto(
                        entity.getTargetType().name(),
                        entity.getTargetId(),
                        entity.getTargetScheduleId(),
                        entity.getStatus().name()))
                .audit(new CrossRefResponse.CrossRefAuditDto(
                        entity.getInvitedBy(),
                        entity.getMessage(),
                        entity.getCreatedAt(),
                        entity.getRespondedAt()))
                .build();
    }
}
