package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentMoveRequest;
import com.mannschaft.app.shift.dto.SlotAssignmentMoveResponse;
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
 * （枠間移動 POST /slots/{slotId}/assignments/move は「更新/割当/削除」列と同じ。移動先の不在・別シフト表・
 *  別チームも 404 SHIFT_002。CMP-261010-1432）
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
     * 割当メンバーを同じシフト表の別の枠へ 1 トランザクションで移す（CMP-261010-1432 P）。
     *
     * <p>認可は移動元の枠に対して {@link #patchSlotAssignments} と同じ（越境・不在は {@code SHIFT_002}、
     * 同チームの非管理者は 403）。移動先の所属照合は認可の後に tx 本体で行う（不在・別シフト表・別チームは
     * いずれも {@code SHIFT_002}）。</p>
     *
     * @param slotId  移動元の枠 ID
     * @param request 移動リクエスト
     * @param userId  操作者ユーザー ID
     * @return 両枠の更新後の状態
     */
    public SlotAssignmentMoveResponse moveAssignment(Long slotId, SlotAssignmentMoveRequest request, Long userId) {
        requireSlotAdmin(slotId, userId);
        return slotService.moveAssignment(slotId, request, userId);
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
