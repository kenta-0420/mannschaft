package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフトポジションの <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W1 の作り替え: 認可（{@code AccessControlService} / {@code ScopeConcealingAccessGate}）を
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * positionId 指定の更新・削除は「{@link ShiftPositionService} の readOnly な scope 解決 → 認可 →
 * {@link ShiftPositionService} の書き込み tx（中で読み直す）」。{@code ?teamId=} 指定の一覧・作成は
 * パスの teamId をそのまま認可に使う（実体由来の scope が無く、非メンバーには常に同一の 403）。
 * 名前を {@code *AccessService} / {@code *AccessGate} にしないのは、呼んだだけで認可シグナル扱いになり
 * 本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前＝#3461 の契約から変えていない。{@code ShiftRequestPositionScopeContractIT} の期待値と同一）</h3>
 * <pre>
 * 主体＼EP                    一覧(teamId)    作成(teamId)    更新(PATCH id)   削除(DELETE id)
 * 部外者(非メンバー)          403 COMMON_002  403 COMMON_002  404 SHIFT_004    404 SHIFT_004
 * 同チーム SUPPORTER          403 COMMON_002  403 COMMON_002  403 COMMON_002   403 COMMON_002
 * 同チーム一般メンバー        200             403 COMMON_002  403 COMMON_002   403 COMMON_002
 * チーム ADMIN/DEPUTY_ADMIN   200             201             200              204
 * 非メンバーの SYSTEM_ADMIN   200             201             200              204
 * メンバーの SYSTEM_ADMIN     200             201             200              204
 * user_roles のみの ADMIN     403 COMMON_002(※) 201           200              204
 * 対象不在(position)          (n/a)           (n/a)           404 SHIFT_004    404 SHIFT_004
 * 親だけ不在                  (n/a: ポジションは shift 内に親を持たない)
 * DB 変更: 拒否・不在はすべて変更なし。成功のみ shift_positions の insert/update/delete。
 * ※ 一覧は isMember（memberships）判定のため、user_roles のみの管理者は是正前から 403 COMMON_002
 *   （応答は変えていない）。
 * </pre>
 * <p>一覧・作成の拒否は teamId の実在・可視性で割れない同一の 403（01_authorization_baseline.md §3.3.1）。
 * 更新・削除の不在コードは {@code SHIFT_004} に統一し、解決（本クラス）と tx 内の読み直しは同じメソッドを通る。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftPositionFacade {

    private static final String TEAM = "TEAM";

    private final ShiftPositionService positionService;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControlService;

    /**
     * チームのポジション一覧を取得する（当該チームのメンバー、SUPPORTER 不可）。
     *
     * @param teamId チームID
     * @param userId 操作者ユーザーID
     * @return ポジション一覧
     * @throws BusinessException 当該チームのメンバーでない場合（COMMON_002 / 403）
     */
    public List<ShiftPositionResponse> listPositions(Long teamId, Long userId) {
        checkTeamMemberAccess(teamId, userId);
        return positionService.listPositions(teamId);
    }

    /**
     * ポジションを作成する（当該チームの ADMIN 以上）。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @param userId 操作者ユーザーID
     * @return 作成されたポジション
     * @throws BusinessException 当該チームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    public ShiftPositionResponse createPosition(Long teamId, CreatePositionRequest req, Long userId) {
        checkTeamAdminAccess(teamId, userId);
        return positionService.createPosition(teamId, req);
    }

    /**
     * ポジションを更新する（当該チームの ADMIN 以上）。
     *
     * @param positionId ポジションID
     * @param req        更新リクエスト
     * @param userId     操作者ユーザーID
     * @return 更新されたポジション
     */
    public ShiftPositionResponse updatePosition(Long positionId, UpdatePositionRequest req, Long userId) {
        ShiftPositionService.PositionScope scope = positionService.resolvePositionScope(positionId);
        accessGate.requireAdminOrConceal(userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_POSITION_NOT_FOUND);
        return positionService.updatePosition(positionId, req);
    }

    /**
     * ポジションを削除する（当該チームの ADMIN 以上）。
     *
     * @param positionId ポジションID
     * @param userId     操作者ユーザーID
     */
    public void deletePosition(Long positionId, Long userId) {
        ShiftPositionService.PositionScope scope = positionService.resolvePositionScope(positionId);
        accessGate.requireAdminOrConceal(userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_POSITION_NOT_FOUND);
        positionService.deletePosition(positionId);
    }

    /**
     * 管理操作の per-scope 認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkTeamAdminAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, teamId, TEAM);
    }

    /**
     * 参照の per-scope 認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkTeamMemberAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, TEAM)
                || accessControlService.isSupporter(userId, teamId, TEAM)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
