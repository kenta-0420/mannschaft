package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフト枠の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W6a: 認可（{@code AccessControlService} / {@code ScopeConcealingAccessGate}）を
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link ShiftSlotService} の readOnly な scope 解決（自ドメインのみ・FOR UPDATE なし）→ 認可 →
 * {@link ShiftSlotService} の tx 本体（中で親スケジュールを FOR UPDATE で読み直す）」。
 * Controller は本クラスだけを呼ぶ。</p>
 *
 * <h3>認可表（是正前の契約から変えていない。ただし K5 で親だけ削除済みの枠起点 EP は SHIFT_002 に揃えた）</h3>
 * <pre>
 * 主体＼EP                  枠一覧(scheduleId)  作成/一括作成(scheduleId)  更新/割当/削除(slotId)
 * 部外者(越境)              404 SHIFT_001       404 SHIFT_001              404 SHIFT_002
 * 同チーム SUPPORTER        403 COMMON_002      403 COMMON_002             403 COMMON_002
 * 同チーム一般メンバー      200(公開済みのみ)   403 COMMON_002             403 COMMON_002
 * チーム ADMIN/DEPUTY_ADMIN 200                 201                        200/204
 * user_roles のみの ADMIN   200                 201                        200/204
 * SYSTEM_ADMIN(非/メンバー) 200                 201                        200/204
 * 対象不在                  404 SHIFT_001       404 SHIFT_001              404 SHIFT_002
 * 親だけ削除済み            404 SHIFT_001       404 SHIFT_001              404 SHIFT_002（是正前は SHIFT_001）
 * </pre>
 * <p>枠一覧の未公開（DRAFT・ARCHIVED で publishedAt なし）は、管理者側以外へは認可結果より先に 404 SHIFT_001、
 * COLLECTING・ADJUSTING は割当だけ伏せる。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftSlotFacade {

    private static final String TEAM = "TEAM";

    private final ShiftSlotService slotService;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControlService;

    /**
     * スケジュールのシフト枠一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return シフト枠一覧
     */
    public List<ShiftSlotResponse> listSlots(Long scheduleId, Long userId) {
        ShiftScheduleScope scope = slotService.resolveScheduleScope(scheduleId);
        boolean privileged = accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdminOrAbove(userId, scope.teamId(), TEAM);
        if (!privileged) {
            if (scope.isHidden()) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            if (!accessControlService.isMember(userId, scope.teamId(), TEAM)) {
                throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            if (accessControlService.isSupporter(userId, scope.teamId(), TEAM)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }
        return slotService.listSlots(scheduleId, privileged);
    }

    /**
     * シフト枠を作成する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param scheduleId スケジュールID
     * @param req        作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠
     */
    public ShiftSlotResponse createSlot(Long scheduleId, CreateShiftSlotRequest req, Long userId) {
        requireScheduleAdmin(scheduleId, userId);
        return slotService.createSlot(scheduleId, req);
    }

    /**
     * シフト枠を一括作成する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param scheduleId スケジュールID
     * @param req        一括作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠一覧
     */
    public List<ShiftSlotResponse> bulkCreateSlots(Long scheduleId, BulkCreateShiftSlotRequest req, Long userId) {
        requireScheduleAdmin(scheduleId, userId);
        return slotService.bulkCreateSlots(scheduleId, req);
    }

    /**
     * シフト枠を更新する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param slotId シフト枠ID
     * @param req    更新リクエスト
     * @param userId 操作者ユーザーID
     * @return 更新されたシフト枠
     */
    public ShiftSlotResponse updateSlot(Long slotId, UpdateShiftSlotRequest req, Long userId) {
        requireSlotAdmin(slotId, userId);
        return slotService.updateSlot(slotId, req, userId);
    }

    /**
     * シフト枠の割当ユーザーを差分更新する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param slotId  シフト枠ID
     * @param request 差分割当リクエスト
     * @param userId  操作者ユーザーID
     * @return 更新後のシフト枠レスポンス
     */
    public ShiftSlotResponse patchSlotAssignments(Long slotId, SlotAssignmentPatchRequest request, Long userId) {
        requireSlotAdmin(slotId, userId);
        return slotService.patchSlotAssignments(slotId, request, userId);
    }

    /**
     * シフト枠を削除する（SYSTEM_ADMIN、または当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param slotId シフト枠ID
     * @param userId 操作者ユーザーID
     */
    public void deleteSlot(Long slotId, Long userId) {
        requireSlotAdmin(slotId, userId);
        slotService.deleteSlot(slotId);
    }

    /** スケジュール起点の管理操作の認可。越境は不在と同一の {@code SHIFT_001}、権限不足は 403。 */
    private void requireScheduleAdmin(Long scheduleId, Long userId) {
        ShiftScheduleScope scope = slotService.resolveScheduleScope(scheduleId);
        accessGate.requireAdminOrConceal(
                userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
    }

    /**
     * 枠起点の管理操作の認可。枠・親スケジュールの不在はどちらも {@code SHIFT_002}、越境も {@code SHIFT_002}、
     * 権限不足は 403。
     */
    private void requireSlotAdmin(Long slotId, Long userId) {
        Long teamId = slotService.resolveSlotTeamId(slotId);
        accessGate.requireAdminOrConceal(userId, teamId, TEAM, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
    }
}
