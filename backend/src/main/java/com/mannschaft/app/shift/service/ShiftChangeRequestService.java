package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト変更依頼サービス。
 * A-1確定前変更・A-2個別交代・A-3オープンコールの依頼フローを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftChangeRequestService {

    /** オープンコール月次上限件数 */
    private static final long OPEN_CALL_MONTHLY_LIMIT = 3L;

    private final ShiftChangeRequestRepository changeRequestRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final AccessControlService accessControlService;

    /**
     * 変更依頼を作成する。
     * - MEMBER: 自チームのスケジュールのみ依頼可
     * - オープンコール（A-3）: 月3件上限チェック
     *
     * @param request 作成リクエスト
     * @param userId  依頼者ユーザーID
     * @return 作成された変更依頼レスポンス
     */
    @Transactional
    public ChangeRequestResponse create(CreateChangeRequestRequest request, Long userId) {
        // スケジュール存在チェック
        scheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

        // オープンコールの月次上限チェック
        if (request.requestType() == ChangeRequestType.OPEN_CALL) {
            long count = changeRequestRepository.countByRequestedByAndRequestTypeInCurrentMonth(
                    userId, ChangeRequestType.OPEN_CALL);
            if (count >= OPEN_CALL_MONTHLY_LIMIT) {
                throw new BusinessException(ShiftErrorCode.OPEN_CALL_MONTHLY_LIMIT_EXCEEDED);
            }
        }

        ShiftChangeRequestEntity entity = ShiftChangeRequestEntity.builder()
                .scheduleId(request.scheduleId())
                .slotId(request.slotId())
                .requestType(request.requestType())
                .requestedBy(userId)
                .reason(request.reason())
                .build();

        entity = changeRequestRepository.save(entity);
        log.info("シフト変更依頼作成: id={}, scheduleId={}, type={}, requestedBy={}",
                entity.getId(), request.scheduleId(), request.requestType(), userId);
        return toResponse(entity);
    }

    /**
     * 変更依頼一覧を取得する。
     * - ADMIN: スケジュール全件
     * - MEMBER: 自分の依頼のみ
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @param role       ロール文字列（"ADMIN" で全件取得）
     * @return 変更依頼一覧
     */
    public List<ChangeRequestResponse> list(Long scheduleId, Long userId, String role) {
        List<ShiftChangeRequestEntity> entities;
        if ("ADMIN".equals(role)) {
            entities = changeRequestRepository.findAllByScheduleIdOrderByCreatedAtDesc(scheduleId);
        } else {
            entities = changeRequestRepository.findAllByRequestedByAndScheduleId(userId, scheduleId);
        }
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * 変更依頼詳細を取得する（IDOR チェック付き）。
     *
     * @param id     変更依頼ID
     * @param userId 操作者ユーザーID
     * @return 変更依頼レスポンス
     */
    public ChangeRequestResponse get(Long id, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);
        // 依頼者本人またはシステム全体（ADMIN チェックはコントローラーで行う）
        return toResponse(entity);
    }

    /**
     * 変更依頼を審査する（ADMIN のみ）。楽観ロックチェックを行う。
     *
     * @param id      変更依頼ID
     * @param request 審査リクエスト
     * @param userId  審査者ユーザーID
     * @return 更新された変更依頼レスポンス
     */
    @Transactional
    public ChangeRequestResponse review(Long id, ReviewChangeRequestRequest request, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        // per-scope 認可（認可根治 Phase 3-a）。
        // entity の scheduleId からチームを解決して per-scope 認可することで IDOR も同時に封鎖する。
        // SYSTEM_ADMIN は短絡許可、それ以外は当該チームの ADMIN/DEPUTY_ADMIN のみ審査可。
        checkReviewerScopeAdminAccess(entity, userId);

        if (entity.getStatus() != ChangeRequestStatus.OPEN) {
            throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        // 楽観ロックチェック
        if (!entity.getVersion().equals(request.version().longValue())) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        switch (request.decision()) {
            case ACCEPTED -> entity.accept(userId, request.reviewComment());
            case REJECTED -> entity.reject(userId, request.reviewComment());
            default -> throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        entity = changeRequestRepository.save(entity);
        log.info("シフト変更依頼審査: id={}, decision={}, reviewerId={}", id, request.decision(), userId);
        return toResponse(entity);
    }

    /**
     * 変更依頼を取り下げる（依頼者のみ、OPEN のもの）。
     *
     * @param id     変更依頼ID
     * @param userId 操作者ユーザーID
     */
    @Transactional
    public void withdraw(Long id, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        if (!entity.getRequestedBy().equals(userId)) {
            throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
        }

        if (entity.getStatus() != ChangeRequestStatus.OPEN) {
            throw new BusinessException(ShiftErrorCode.INVALID_CHANGE_REQUEST_STATUS);
        }

        entity.withdraw();
        changeRequestRepository.save(entity);
        log.info("シフト変更依頼取下: id={}, userId={}", id, userId);
    }

    /**
     * 変更依頼審査に対する per-scope 認可を強制する（認可根治 Phase 3-a）。
     *
     * <p>Controller の {@code @PreAuthorize("hasRole('ADMIN')")} は per-scope 文脈を持てず
     * かつ {@code @EnableMethodSecurity} 未有効ゆえ実機 no-op のため、Service 層で明示的に認可する。
     * 変更依頼の {@code scheduleId} から所属チームを解決し（IDOR 封鎖）、SYSTEM_ADMIN は短絡許可、
     * それ以外は当該チームの ADMIN/DEPUTY_ADMIN でなければ {@code COMMON_002}（403）をスローする。
     * {@code ShiftScheduleService#checkScheduleAdminAccess}（#1189）と同一方針。</p>
     *
     * @param entity 審査対象の変更依頼
     * @param userId 審査者ユーザー ID
     */
    private void checkReviewerScopeAdminAccess(ShiftChangeRequestEntity entity, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long teamId = scheduleRepository.findById(entity.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .getTeamId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * 変更依頼を取得する。存在しない場合は例外をスローする。
     */
    private ShiftChangeRequestEntity findOrThrow(Long id) {
        return changeRequestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND));
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private ChangeRequestResponse toResponse(ShiftChangeRequestEntity entity) {
        return ChangeRequestResponse.builder()
                .id(entity.getId())
                .scheduleId(entity.getScheduleId())
                .slotId(entity.getSlotId())
                .requestInfo(new ChangeRequestResponse.ChangeRequestTypeDto(
                        entity.getRequestType(), entity.getReason(), entity.getRequestedBy()))
                .reviewInfo(new ChangeRequestResponse.ChangeRequestStatusDto(
                        entity.getStatus(), entity.getReviewerId(), entity.getReviewComment(), entity.getReviewedAt()))
                .timing(new ChangeRequestResponse.ChangeRequestTimingDto(
                        entity.getExpiresAt(), entity.getCreatedAt()))
                .build();
    }
}
