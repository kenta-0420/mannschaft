package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
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
    private final ShiftSlotRepository slotRepository;
    private final AccessControlService accessControlService;

    /**
     * 変更依頼を作成する。
     * - MEMBER: 自チームのスケジュールのみ依頼可
     * - オープンコール（A-3）: 月3件上限チェック
     *
     * <p><b>認可（認可根治 Wave7）:</b> 「MEMBER: 自チームのスケジュールのみ依頼可」という方針を、
     * {@code scheduleId} から解決したチームへの所属をサーバー側で強制することで実効化する
     * （同一クラスの {@code list}（Wave6）・{@code withdraw} と同じ「実体由来 scope」の作法）。</p>
     *
     * <p><b>BOLA 封鎖:</b> {@code slotId} が指定された場合、その枠が当該スケジュールに属することを
     * 検証する。属さない枠は<b>存在を秘匿して 404</b>（{@code SHIFT_SLOT_NOT_FOUND}）とし、
     * 他チームの枠 ID の存在有無を観測させない。</p>
     *
     * @param request 作成リクエスト
     * @param userId  依頼者ユーザーID
     * @return 作成された変更依頼レスポンス
     */
    @Transactional
    public ChangeRequestResponse create(CreateChangeRequestRequest request, Long userId) {
        // スケジュール存在チェック＋所属チーム解決（scope はクライアント入力でなく実体由来）
        ShiftScheduleEntity schedule = scheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        checkRequesterMembership(schedule.getTeamId(), userId);
        checkSlotBelongsToSchedule(request.slotId(), request.scheduleId());

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
     *
     * <p><b>認可（認可根治 Wave6 / 権限昇格の封鎖）:</b> 旧実装は呼び出し側から渡された
     * {@code role} 文字列（実体はクエリパラメータ {@code ?role=}）で全件返却か自分の分のみかを
     * 分岐していた。<b>クライアント入力を認可判断の材料にしていた</b>ため、一般メンバーが
     * {@code ADMIN} を自称するだけで他人の依頼を含む全件を取得できた。
     * 本実装では {@code role} を撤廃し、{@code scheduleId} から解決したチームに対する
     * サーバー側のロール判定でのみ分岐する。</p>
     *
     * <p>粒度は同ドメインの兄弟 API に合わせる。当該チームの ADMIN/DEPUTY_ADMIN（および
     * SYSTEM_ADMIN）はスケジュール全件、一般メンバーは自分の依頼のみ、非メンバーは
     * {@code COMMON_002}（403）とする。非メンバーを 403 とするのは
     * {@code ShiftPdfService#checkMemberAndNotSupporter} と同一方針。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return 変更依頼一覧
     */
    public List<ChangeRequestResponse> list(Long scheduleId, Long userId) {
        Long teamId = resolveTeamId(scheduleId);

        List<ShiftChangeRequestEntity> entities;
        if (isScopeAdmin(userId, teamId)) {
            entities = changeRequestRepository.findAllByScheduleIdOrderByCreatedAtDesc(scheduleId);
        } else if (accessControlService.isMember(userId, teamId, "TEAM")) {
            entities = changeRequestRepository.findAllByRequestedByAndScheduleId(userId, scheduleId);
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * 変更依頼詳細を取得する（IDOR チェック付き）。
     *
     * <p><b>認可（認可根治 Wave6 / 死文だった Javadoc の実装）:</b> 旧実装は
     * 「IDOR チェック付き」と Javadoc に明記しながら<b>本体に照合コードが無く</b>、
     * 認証済みであれば任意の ID の変更依頼を閲覧できた。本実装で実際の照合を行う。</p>
     *
     * <p>閲覧を許すのは「依頼者本人」または「当該シフトの所属チームの ADMIN/DEPUTY_ADMIN」
     *（SYSTEM_ADMIN は短絡許可）のみ。それ以外は<b>存在を秘匿するため 404</b>
     *（{@code CHANGE_REQUEST_NOT_FOUND}）を返す。403 と 404 を撃ち分けると
     * ID の存在有無が観測できてしまうため、越境時は未存在と同じ応答に寄せている。</p>
     *
     * @param id     変更依頼ID
     * @param userId 操作者ユーザーID
     * @return 変更依頼レスポンス
     */
    public ChangeRequestResponse get(Long id, Long userId) {
        ShiftChangeRequestEntity entity = findOrThrow(id);

        if (entity.getRequestedBy().equals(userId)) {
            return toResponse(entity);
        }
        if (isScopeAdmin(userId, resolveTeamId(entity.getScheduleId()))) {
            return toResponse(entity);
        }
        // 越境は存在秘匿（未存在と同一応答）
        throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
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
     * <p>Controller の {@code @PreAuthorize("hasRole('ADMIN')")} は per-scope 文脈を持てないため、
     * Service 層で明示的に認可する。
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
     * 変更依頼の申請者が当該チームのメンバーであることを強制する（認可根治 Wave7）。
     *
     * <p>粒度は「メンバー」。変更依頼は一般メンバーの日常操作であり管理者に絞る性質のものではない。
     * 非メンバーは {@code COMMON_002}（403）で弾く（{@code list} と同一方針）。
     * ArchUnit 認可番人の委譲追跡上限（2 ホップ）に収めるため
     * {@link AccessControlService} を本メソッドから直接呼ぶ。</p>
     *
     * @param teamId スケジュール実体から解決したチーム ID
     * @param userId 申請者ユーザー ID
     */
    private void checkRequesterMembership(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }

    /**
     * 指定された枠が当該スケジュールに属することを検証する（BOLA 封鎖）。
     *
     * <p>{@code slotId} は任意項目（NULL = スケジュール全体への依頼）のため、NULL は素通し。
     * 非 NULL のとき、枠が存在しない／別スケジュールの枠である場合はいずれも
     * {@code SHIFT_SLOT_NOT_FOUND}（404）とし、他チームの枠 ID の存在有無を漏らさない。</p>
     *
     * @param slotId     リクエスト由来の枠 ID（null 可）
     * @param scheduleId 対象スケジュール ID
     */
    private void checkSlotBelongsToSchedule(Long slotId, Long scheduleId) {
        if (slotId == null) {
            return;
        }
        ShiftSlotEntity slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        if (!scheduleId.equals(slot.getScheduleId())) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }
    }

    /**
     * スケジュール ID から所属チーム ID を解決する（scope をパス/クエリ入力でなく実体由来にする）。
     *
     * @param scheduleId スケジュール ID
     * @return 所属チーム ID
     */
    private Long resolveTeamId(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .getTeamId();
    }

    /**
     * 当該チームに対する管理者（SYSTEM_ADMIN 短絡 or ADMIN/DEPUTY_ADMIN）かを判定する。
     *
     * <p>{@code checkAdminOrAbove} と異なり例外を投げず真偽を返す。一覧の返却範囲切替や
     * 詳細の可視判定のように「弾く」のでなく「分岐する」用途で使う。</p>
     *
     * @param userId 操作者ユーザー ID
     * @param teamId チーム ID
     * @return 管理者相当なら true
     */
    private boolean isScopeAdmin(Long userId, Long teamId) {
        return accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdminOrAbove(userId, teamId, "TEAM");
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
