package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * シフト交代リクエストの <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W2: 認可（{@code AccessControlService} / {@code ScopeConcealingAccessGate}）を
 * {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * 流れは「{@link ShiftSwapService} の readOnly な scope 解決（自ドメインのみ）→ 認可 →
 * {@link ShiftSwapService} の書き込み tx（中で読み直す）」。Controller は本クラスだけを呼ぶ。
 * 名前を {@code *AccessService} / {@code *AccessGate} にしないのは、呼んだだけで認可シグナル扱いになり
 * 本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前＝#3528 の契約から変えていない。IT の期待値と同一）</h3>
 * <pre>
 * 主体＼EP                      一覧(teamId)   作成(slot)        承諾(swap)        承認/却下(swap)   取消(swap)
 * 部外者(非メンバー)            403 COMMON_002 404 SHIFT_002     404 SHIFT_005     404 SHIFT_005     404 SHIFT_005
 * 同チーム SUPPORTER            403 COMMON_002 403 COMMON_002    403 COMMON_002    403 COMMON_002    403 COMMON_002
 * 同チーム一般メンバー          200(関係分のみ) 201              200(状態次第)     403 COMMON_002    403 COMMON_002(本人以外)
 * チーム ADMIN/DEPUTY_ADMIN     200(全件)      201              200(状態次第)     200(状態次第)     204(状態次第)
 * 申請者本人                    200            -                (自己承諾は 400 SWAP_SELF_REQUEST) - 204(PENDING のみ。親の存在に依存しない)
 * 非メンバーの SYSTEM_ADMIN     200(全件)      201              200(状態次第)     200(状態次第)     204(状態次第)
 * メンバーの SYSTEM_ADMIN       200(全件)      201              200(状態次第)     200(状態次第)     204(状態次第)
 * user_roles のみの ADMIN       200(全件)      403 COMMON_002   403 COMMON_002    200(状態次第)     204(状態次第)
 * 対象不在(swap/slot)           (n/a)          404 SHIFT_002    404 SHIFT_005     404 SHIFT_005     404 SHIFT_005
 * 親だけ不在(枠→スケジュール)   (n/a)          404 SHIFT_001    404 SHIFT_001     404 SHIFT_001     404 SHIFT_001(本人以外)
 * DB 変更: 拒否・不在はすべて変更なし。成功のみ swap 行の insert/update。
 * </pre>
 * <p>親だけ不在のコードを対象リソースの不在コードへ畳まず是正前（SHIFT_001/SHIFT_002）のまま保つのは、
 * 「応答を変えない」契約を優先したため。解決（本クラス）と tx 内の読み直しは同じメソッドを通るので
 * コードは常に一致する。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftSwapFacade {

    private static final String TEAM = "TEAM";

    private final ShiftSwapService swapService;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControlService;

    /**
     * 指定チームの交代リクエスト一覧を取得する。
     *
     * <p>SYSTEM_ADMIN または当該チームの ADMIN/DEPUTY_ADMIN は全件、一般メンバー（SUPPORTER 不可）は
     * 自分に関係する依頼のみ、それ以外は 403（{@code COMMON_002}）。一覧は ID を叩く EP ではなく
     * チームの存在有無が応答で割れないため、403 のまま。</p>
     *
     * @param teamId 対象チーム ID
     * @param status ステータスフィルタ
     * @param userId 操作者ユーザー ID
     * @return 交代リクエスト一覧
     */
    public List<SwapRequestResponse> listSwapRequests(Long teamId, String status, Long userId) {
        boolean privileged = checkListAccessAndIsPrivileged(teamId, userId);
        return swapService.listSwapRequests(teamId, status, userId, privileged);
    }

    /**
     * 交代リクエストを作成する（対象シフト枠の所属チームのメンバー、SUPPORTER 不可）。
     *
     * @param req    作成リクエスト
     * @param userId 申請者 ID
     * @return 作成された交代リクエスト
     */
    public SwapRequestResponse createSwapRequest(CreateSwapRequestRequest req, Long userId) {
        ShiftSwapService.SwapScope scope = swapService.resolveSlotScope(req.getSlotId());
        // 越境（他チームの slotId）は不在と同一応答（SHIFT_SLOT_NOT_FOUND）に畳む。
        accessGate.requireMemberOrConceal(
                userId, scope.teamId(), TEAM, ShiftErrorCode.SHIFT_SLOT_NOT_FOUND, true);
        return swapService.createSwapRequest(req, userId);
    }

    /**
     * 交代リクエストを承諾する（当該チームのメンバー、SUPPORTER 不可）。
     *
     * @param swapId     交代リクエスト ID
     * @param accepterId 承諾者 ID
     * @return 更新された交代リクエスト
     */
    public SwapRequestResponse acceptSwapRequest(Long swapId, Long accepterId) {
        ShiftSwapService.SwapScope scope = swapService.resolveSwapScope(swapId);
        accessGate.requireMemberOrConceal(
                accepterId, scope.teamId(), TEAM, ShiftErrorCode.SWAP_REQUEST_NOT_FOUND, true);
        return swapService.acceptSwapRequest(swapId, accepterId);
    }

    /**
     * 交代リクエストを承認・却下する（当該チームの ADMIN 以上）。
     *
     * @param swapId  交代リクエスト ID
     * @param req     承認・却下リクエスト
     * @param adminId 管理者 ID
     * @return 更新された交代リクエスト
     */
    public SwapRequestResponse resolveSwapRequest(Long swapId, ResolveSwapRequestRequest req, Long adminId) {
        ShiftSwapService.SwapScope scope = swapService.resolveSwapScope(swapId);
        accessGate.requireAdminOrConceal(
                adminId, scope.teamId(), TEAM, ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        return swapService.resolveSwapRequest(swapId, req, adminId);
    }

    /**
     * 交代リクエストをキャンセルする（申請者本人は所属を問わず許可、それ以外は当該チームの ADMIN 以上）。
     *
     * @param swapId 交代リクエスト ID
     * @param userId 操作者 ID
     */
    public void cancelSwapRequest(Long swapId, Long userId) {
        ShiftSwapService.SwapScope scope = swapService.resolveCancelScope(swapId, userId);
        if (!userId.equals(scope.requesterId())) {
            accessGate.requireOwnerOrAdminOrConceal(
                    userId, scope.teamId(), TEAM, scope.requesterId(), ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        }
        swapService.cancelSwapRequest(swapId, userId);
    }

    /**
     * 一覧 API の per-scope 認可を行い、「全件を見てよい立場か」を返す。
     *
     * @param teamId 対象チーム ID
     * @param userId 操作者ユーザー ID
     * @return SYSTEM_ADMIN または当該チームの ADMIN/DEPUTY_ADMIN なら true
     * @throws BusinessException 当該チームのメンバーでない、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private boolean checkListAccessAndIsPrivileged(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (accessControlService.isAdminOrAbove(userId, teamId, TEAM)) {
            return true;
        }
        // 一般メンバー（SUPPORTER は不可）。承諾できる立場と同じ条件に揃える。
        if (!accessControlService.isMember(userId, teamId, TEAM)
                || accessControlService.isSupporter(userId, teamId, TEAM)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return false;
    }
}
