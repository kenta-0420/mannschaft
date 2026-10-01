package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフト変更依頼の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W2: 認可を {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を
 * <b>付けない</b>（クラスにもメソッドにも）。流れは「{@link ShiftChangeRequestService} の readOnly な scope 解決
 * （自ドメインのみ）→ 認可 → {@link ShiftChangeRequestService} の tx（中で読み直す）」。
 * Controller は本クラスだけを呼ぶ。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前＝#3528 の契約から変えていない）</h3>
 * <pre>
 * 主体＼EP                    作成(schedule)    一覧(schedule)   詳細(id)          審査(id)         取下げ(id)
 * 部外者(非メンバー)          404 SHIFT_001     404 SHIFT_001    404 SHIFT_030     404 SHIFT_030    404 SHIFT_030
 * 同チーム一般メンバー        201               200(自分の分)    200(本人)/404 SHIFT_030(他人の依頼) 403 COMMON_002 403 SHIFT_019(他人の依頼)
 * チーム ADMIN/DEPUTY_ADMIN   201               200(全件)        200              200(状態次第)    403 SHIFT_019
 * 依頼者本人                  201               200(自分の分)    200              (一般なら 403)   204(OPEN のみ)
 * 非メンバーの SYSTEM_ADMIN   403 COMMON_002    200(全件)        200              200(状態次第)    403 SHIFT_019
 * メンバーの SYSTEM_ADMIN     201               200(全件)        200              200(状態次第)    403 SHIFT_019
 * user_roles のみの ADMIN     403 COMMON_002    200(全件)        200              200(状態次第)    403 SHIFT_019
 * 対象不在(依頼/スケジュール) 404 SHIFT_001     404 SHIFT_001    404 SHIFT_030     404 SHIFT_030    404 SHIFT_030
 * 親だけ不在(スケジュール)    404 SHIFT_001     404 SHIFT_001    404 SHIFT_001(本人以外) 404 SHIFT_001 404 SHIFT_001(本人以外。SYSTEM_ADMIN は 403 SHIFT_019)
 * DB 変更: 拒否・不在はすべて変更なし。成功のみ変更依頼行の insert/update。
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ShiftChangeRequestFacade {

    private static final String TEAM = "TEAM";

    private final ShiftChangeRequestService changeRequestService;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControlService;

    /**
     * 変更依頼を作成する（スケジュール所属チームのメンバー。SUPPORTER も可）。
     *
     * <p>Gate は冒頭で SYSTEM_ADMIN を通すが、是正前は {@code checkMembership}（memberships のみ）で
     * 非メンバーの SYSTEM_ADMIN も 403 {@code COMMON_002} だった。挙動を変えないため先に弾く。</p>
     *
     * @param request 作成リクエスト
     * @param userId  依頼者ユーザー ID
     * @return 作成された変更依頼
     */
    public ChangeRequestResponse create(CreateChangeRequestRequest request, Long userId) {
        Long teamId = changeRequestService.resolveScheduleTeamId(request.scheduleId());
        if (accessControlService.isSystemAdmin(userId)) {
            accessControlService.checkMembership(userId, teamId, TEAM);
        }
        accessGate.requireMemberOrConceal(
                userId, teamId, TEAM, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, false);
        return changeRequestService.create(request, userId);
    }

    /**
     * 変更依頼一覧を取得する。管理者（SYSTEM_ADMIN・ADMIN 以上）は全件、一般メンバーは自分の依頼のみ。
     * 越境（非メンバー）は不在と同一応答（{@code SHIFT_SCHEDULE_NOT_FOUND}）に畳む。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @return 変更依頼一覧
     */
    public List<ChangeRequestResponse> list(Long scheduleId, Long userId) {
        Long teamId = changeRequestService.resolveScheduleTeamId(scheduleId);
        // 同一チーム内は admin/member どちらでも許可されるため、Gate の permitted 条件に isMember を渡し、
        // 拒否経路でのみ越境判定を行わせる。
        accessGate.requireOrConceal(
                userId, teamId, TEAM,
                () -> accessControlService.isMember(userId, teamId, TEAM),
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, CommonErrorCode.COMMON_002);
        return changeRequestService.list(scheduleId, userId, isScopeAdmin(userId, teamId));
    }

    /**
     * 変更依頼詳細を取得する。依頼者本人または当該チーム管理者のみ。それ以外は存在秘匿で 404
     * （{@code CHANGE_REQUEST_NOT_FOUND}）。
     *
     * @param id     変更依頼 ID
     * @param userId 操作者ユーザー ID
     * @return 変更依頼
     */
    public ChangeRequestResponse get(Long id, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        if (!head.requestedBy().equals(userId)
                && !isScopeAdmin(userId, changeRequestService.resolveScheduleTeamId(head.scheduleId()))) {
            // 越境は存在秘匿（未存在と同一応答）
            throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        }
        return changeRequestService.get(id, userId);
    }

    /**
     * 変更依頼を審査する（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param id      変更依頼 ID
     * @param request 審査リクエスト
     * @param userId  審査者ユーザー ID
     * @return 更新された変更依頼
     */
    public ChangeRequestResponse review(Long id, ReviewChangeRequestRequest request, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        Long teamId = changeRequestService.resolveScheduleTeamId(head.scheduleId());
        accessGate.requireAdminOrConceal(userId, teamId, TEAM, ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        return changeRequestService.review(id, request, userId);
    }

    /**
     * 変更依頼を取り下げる（依頼者本人のみ。SYSTEM_ADMIN・管理者による代理取下げは対象外）。
     *
     * <p>本人でない場合、SYSTEM_ADMIN および当該チームの所属者（user_roles のみの管理者を含む）には
     * {@code ACCESS_DENIED}（403 / SHIFT_019）、所属しない越境の場合のみ不在と同一応答
     * （{@code CHANGE_REQUEST_NOT_FOUND}）。Gate は forbidden コードを {@code COMMON_002} 固定で持つため、
     * SHIFT_019 を維持する本メソッドは同ゲートの判定対象（SYSTEM_ADMIN / isAdminOrAbove / isMember）だけを踏襲する。</p>
     *
     * @param id     変更依頼 ID
     * @param userId 操作者ユーザー ID
     */
    public void withdraw(Long id, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        if (!head.requestedBy().equals(userId)) {
            // SYSTEM_ADMIN も本人でなければ取下げ不可（従来どおり 403）。404 に畳まず ACCESS_DENIED を返す。
            if (accessControlService.isSystemAdmin(userId)) {
                throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
            }
            Long teamId = changeRequestService.resolveScheduleTeamId(head.scheduleId());
            if (accessControlService.isAdminOrAbove(userId, teamId, TEAM)
                    || accessControlService.isMember(userId, teamId, TEAM)) {
                throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
            }
            throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        }
        changeRequestService.withdraw(id, userId);
    }

    /**
     * 当該チームに対する管理者（SYSTEM_ADMIN 短絡 or ADMIN/DEPUTY_ADMIN）かを判定する。
     * 例外を投げず真偽を返す（一覧の返却範囲切替・詳細の可視判定用）。
     */
    private boolean isScopeAdmin(Long userId, Long teamId) {
        return accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdminOrAbove(userId, teamId, TEAM);
    }
}
