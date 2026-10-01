package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフト希望の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W1 の作り替え: 認可（{@code ScopeConcealingAccessGate}）を {@code @Transactional} の外へ出す。
 * 本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link ShiftRequestService} の readOnly な scope 解決（自ドメインのみ）→ 認可 →
 * {@link ShiftRequestService} の tx（中で読み直す。親スケジュール行の FOR UPDATE は認可の後）」。
 * Controller は本クラスを呼ぶ（{@code @SelfScopedEndpoint} の {@code listMyRequests} だけは
 * {@link ShiftRequestService} を直接呼ぶ）。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前＝#3461 の契約から変えていない。{@code ShiftRequestPositionScopeContractIT} の期待値と同一）</h3>
 * <pre>
 * 主体＼EP                    一覧/サマリー(scheduleId)  提出(POST)            更新(PATCH)            削除(DELETE)
 * 部外者(非メンバー)          404 SHIFT_001             404 SHIFT_001         404 SHIFT_003          404 SHIFT_003
 * 同チーム SUPPORTER          403 COMMON_002            403 COMMON_002        403 COMMON_002         403 COMMON_002
 * 同チーム一般メンバー        403 COMMON_002            201                   403 COMMON_002(他人分)  403 COMMON_002(他人分)
 * チーム ADMIN/DEPUTY_ADMIN   200                       201                   200(状態次第)          204
 * 提出者本人                  (一般メンバーと同じ)      201                   200(状態次第)          204
 * 非メンバーの SYSTEM_ADMIN   200                       201                   200(状態次第)          204
 * メンバーの SYSTEM_ADMIN     200                       201                   200(状態次第)          204
 * user_roles のみの ADMIN     200                       403 COMMON_002        200(状態次第)          204
 * 対象不在(schedule/request)  404 SHIFT_001             404 SHIFT_001         404 SHIFT_003          404 SHIFT_003
 * 親だけ不在(論理削除)        404 SHIFT_001             404 SHIFT_001         404 SHIFT_003          404 SHIFT_003
 *                             (SYSTEM_ADMIN・本人を含め全主体で同一。認可より前に親の生存を確認する)
 * DB 変更: 拒否・不在はすべて変更なし。成功のみ shift_requests の insert/update/論理削除。
 * </pre>
 * <p>不在コードの揃え方（K5）: scheduleId 指定系は {@code SHIFT_001}、希望 ID 指定系は親が消えていても
 * 希望の不在コード {@code SHIFT_003}。解決（本クラス）と tx 内の読み直しは同じ経路を通るのでコードは常に一致する。
 * 本人の経路で是正前から親に依存しないものは無い（本人でも親が消えれば 404）ため、K1 の競合の対象外は無い。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftRequestFacade {

    private static final String TEAM = "TEAM";

    private final ShiftRequestService requestService;
    private final ScopeConcealingAccessGate accessGate;

    /**
     * スケジュールのシフト希望一覧を取得する（他メンバー分を含むため管理者のみ）。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return シフト希望一覧
     */
    public List<ShiftRequestResponse> listRequests(Long scheduleId, Long userId) {
        ShiftRequestService.ScheduleScope scope = requestService.resolveScheduleScope(scheduleId);
        accessGate.requireAdminOrConceal(userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        return requestService.listRequests(scheduleId);
    }

    /**
     * シフト希望を提出する（当該チームの在籍メンバー、SUPPORTER 不可）。
     *
     * @param req    提出リクエスト
     * @param userId ユーザーID
     * @return 提出されたシフト希望
     */
    public ShiftRequestResponse submitRequest(CreateShiftRequestRequest req, Long userId) {
        ShiftRequestService.ScheduleScope scope = requestService.resolveScheduleScope(req.getScheduleId());
        // 越境は不在と同一の SHIFT_001。user_roles のみの ADMIN は許可せず 403。
        accessGate.requireMemberOrConceal(userId, scope.teamId(), TEAM,
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, true);
        return requestService.submitRequest(req, userId);
    }

    /**
     * シフト希望を更新する（提出者本人、または当該チームの ADMIN 以上）。
     *
     * @param requestId 希望ID
     * @param req       更新リクエスト
     * @param userId    ユーザーID
     * @return 更新されたシフト希望
     */
    public ShiftRequestResponse updateRequest(Long requestId, UpdateShiftRequestRequest req, Long userId) {
        ShiftRequestService.RequestScope scope = requestService.resolveRequestScope(requestId);
        accessGate.requireOwnerOrAdminOrConceal(userId, scope.teamId(), TEAM, scope.ownerUserId(),
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND);
        return requestService.updateRequest(requestId, req);
    }

    /**
     * シフト希望を削除する（提出者本人、または当該チームの ADMIN 以上）。
     *
     * @param requestId 希望ID
     * @param userId    操作者ユーザーID
     */
    public void deleteRequest(Long requestId, Long userId) {
        ShiftRequestService.RequestScope scope = requestService.resolveRequestScope(requestId);
        accessGate.requireOwnerOrAdminOrConceal(userId, scope.teamId(), TEAM, scope.ownerUserId(),
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND);
        requestService.deleteRequest(requestId);
    }

    /**
     * シフト希望提出サマリーを取得する（管理者のみ）。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return 提出サマリー
     */
    public ShiftRequestSummaryResponse getRequestSummary(Long scheduleId, Long userId) {
        ShiftRequestService.ScheduleScope scope = requestService.resolveScheduleScope(scheduleId);
        accessGate.requireAdminOrConceal(userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        return requestService.getRequestSummary(scheduleId, userId);
    }
}
