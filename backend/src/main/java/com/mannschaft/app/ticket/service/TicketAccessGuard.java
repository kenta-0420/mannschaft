package com.mannschaft.app.ticket.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ticket.TicketErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回数券（F08.5）ドメインの認可ガード（認可根治戦役 Wave5）。
 *
 * <p>本ドメインは Controller・Service ともに認可シグナルを一切持たず、
 * URL パスの {@code teamId} を検証なしに信頼していた。ログイン済みであれば誰でも
 * 他チームのスタッフ操作（発行・消化・返金・統計エクスポート）に到達でき、
 * 顧客面では他人の購入情報へ到達できる状態だったため、全 public 入口に本ガードを敷く
 * （{@code feedback_authz_gate_on_public_entry_not_shared_method}:
 * 認可ガードは public 入口に集約し、バッチ等と共有される内部 finder には敷かない）。</p>
 *
 * <h3>二段防御</h3>
 * <ul>
 *   <li><b>一段目（本ガード）</b>: URL パスの {@code teamId} に対する membership/ADMIN 検証。
 *       非メンバー・非 ADMIN は 403（COMMON_002）。</li>
 *   <li><b>二段目（entity 由来 scope 束縛）</b>: 各 Service の {@code findByIdAndTeamId} が
 *       対象 entity の所属チームを突合し、越境 ID は 404（{@code TICKET_001/002/004}）で存在秘匿する。
 *       顧客面ではさらに {@link #requireBookOwner} が所有者一致を要求する。</li>
 * </ul>
 *
 * <h3>粒度</h3>
 * <ul>
 *   <li><b>スタッフ面</b>（商品の作成・更新・削除／発行・消化・取消・返金・延長・QR 消化・一括消化／
 *       発行一覧・詳細・統計・エクスポート／顧客チケットサマリ）＝ {@link #requireTeamAdmin}。
 *       いずれも全顧客の購入履歴・氏名・売上といった機微情報を扱うため ADMIN/DEPUTY_ADMIN に限定する。</li>
 *   <li><b>チーム内 read</b>（販売中の商品一覧）＝ {@link #requireTeamMember}。
 *       購入導線のため SUPPORTER も通す（{@code memberships} 由来の判定）。
 *       ただし販売停止中を含む全件参照（{@code includeInactive=true}）は運用情報のため ADMIN に限定する。</li>
 *   <li><b>顧客面</b>（自分のチケット一覧・ウィジェット・購入）＝ {@code userId} による自己スコープで自足するため
 *       スコープガード非適用（非メンバーが叩いても自分の 0 件が返るのみで他者情報は露出しない）。
 *       ただし ID を指定して単票を引く詳細・領収書・QR は {@link #requireBookOwner} で所有者一致を必須とする。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 指定チームのメンバー（SUPPORTER を含む）であることを要求する（チーム内 read の入口）。
     * 非メンバーは 403（COMMON_002）。
     *
     * @param teamId チーム ID（URL パス由来）
     * @param userId 操作ユーザー ID
     */
    public void requireTeamMember(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }

    /**
     * 指定チームの ADMIN/DEPUTY_ADMIN であることを要求する（スタッフ面の入口）。
     * 非 ADMIN は 403（COMMON_002）。
     *
     * @param teamId チーム ID（URL パス由来）
     * @param userId 操作ユーザー ID
     */
    public void requireTeamAdmin(Long teamId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
    }

    /**
     * 対象チケットの所有者本人であることを要求する（顧客面の単票参照の入口）。
     *
     * <p>他人のチケットに対しては 403 ではなく <b>404（{@code TICKET_002}）</b> を返し、
     * 「その ID のチケットが存在するか否か」を秘匿する。403 を返すと ID の実在が判別でき、
     * 購入者の存在自体が推測可能になるため（IDOR 秘匿の慣例）。</p>
     *
     * @param ownerUserId   チケットに記録された所有者 ID（entity 由来）
     * @param currentUserId 操作ユーザー ID
     */
    public void requireBookOwner(Long ownerUserId, Long currentUserId) {
        if (ownerUserId == null || !ownerUserId.equals(currentUserId)) {
            throw new BusinessException(TicketErrorCode.BOOK_NOT_FOUND);
        }
    }
}
