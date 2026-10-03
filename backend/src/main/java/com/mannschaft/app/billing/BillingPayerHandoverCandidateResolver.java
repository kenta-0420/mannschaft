package com.mannschaft.app.billing;

import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 柱③-B 請求担当引継: 引継先候補（＝承諾できる者）の単一の定義（設計書 §5.2・§5.5・§5.6）。
 *
 * <p>設計書は引継の受け手を一貫して「対象スコープの<b>他</b> ADMIN」と表現する（§5.2 の通知先、
 * §5.5 の「引継先候補なし」5分岐、§3 のフロー図「対象スコープの他 ADMIN が承諾」）。
 * この「他 ADMIN」の判定は<b>申請時（候補の有無）と承諾時（承諾者の適格性）の両方で必要</b>であり、
 * 二箇所に別々の実装を置くと片方だけが緩む。実際に Codex 検分1巡目 P1-2 で、申請時は候補集合で
 * 絞っているのに承諾時は {@code requireCanManage} しか見ておらず、<b>旧 payer 本人の自己承諾</b>や
 * <b>候補外の権限保持者（DEPUTY_ADMIN 等）による承諾</b>が通る不整合が検出された。
 * 本クラスはその判定を1箇所に集約し、両経路が同じ集合を参照することを保証する。</p>
 *
 * <p><b>旧 payer を除外する理由</b>: 引継は「旧 payer から他者へ支払responsibilityを移す」操作であり、
 * 旧 payer 自身が承諾しても支払担当は変わらない。§5.5 ①（ADMIN が退会者1人だけ）を
 * 「候補なし＝ FAILED」と定義していることからも、旧 payer は候補に含まれない。</p>
 */
@Component
@RequiredArgsConstructor
public class BillingPayerHandoverCandidateResolver {

    /** 引継先候補として認めるロール。{@code requireCanManage} が通る権限より狭い。 */
    private static final String ADMIN_ROLE = "ADMIN";

    private final RoleService roleService;

    /**
     * 対象スコープの引継先候補（旧 payer を除いた ADMIN）の user_id 一覧を返す（設計書 §5.5 ①②・AC-10/17/18）。
     *
     * <p><b>越境は Service 経由</b>（{@link RoleService}）で行う。{@code role} ドメインの Repository を
     * 直接 DI するのは {@code CrossDomainRepositoryDependencyArchTest}（D-5）違反である。</p>
     *
     * <p><b>「退会予定」の除外はクエリ側で成立している</b>: {@link RoleService} の候補クエリは
     * いずれも {@code users.deleted_at IS NULL AND users.status = 'ACTIVE'} で絞る。退会申請
     * （{@code UserService#requestWithdrawal} → {@code UserEntity#requestDeletion}）は撤回ウィンドウ中でも
     * {@code deleted_at} を立てるため、退会予定の ADMIN はそもそもこの一覧に現れない。よって
     * 「ADMIN が0人」（分岐①）と「他 ADMIN 全員が退会予定」（分岐②）は同じ空リストとして現れ、
     * どちらも {@code HANDOVER_NO_CANDIDATE} になる。</p>
     *
     * <p><b>ORG / TEAM とも ADMIN ロールのみに厳密に絞る</b>（設計書 §5.6「当該スコープの ADMIN ロールを
     * 持つユーザーのみ許可」・AC-11）。ORG 側で {@code getAdminUserIdsByOrganizationId} を使ってはならない
     * ——同メソッドは名前に反して DEPUTY_ADMIN も返すため、DEPUTY_ADMIN が引継先候補に混入し、
     * 承諾者判定を本メソッドに委ねている以上そのまま<b>承諾もできてしまう</b>（Codex検分2巡目 P1-1）。
     * ロール名を明示する {@code getUserIdsByOrganizationIdAndRoleName} を使い、TEAM 側の
     * {@code getUserIdsByTeamIdAndRoleName} と母集合の定義を揃える。</p>
     *
     * @param oldPayerUserId 除外する旧 payer（{@code null} なら除外しない。自分へは引き継げない）
     * @return 候補の user_id（重複なし。該当なしなら空リスト）
     */
    public List<Long> candidateAdminUserIds(
            EntitlementScopeKind scopeKind, Long scopeId, Long oldPayerUserId) {

        List<Long> admins = switch (scopeKind) {
            case TEAM -> roleService.getUserIdsByTeamIdAndRoleName(scopeId, ADMIN_ROLE);
            case ORG -> roleService.getUserIdsByOrganizationIdAndRoleName(scopeId, ADMIN_ROLE);
            case USER -> List.of(); // 呼び出し前に弾いているが switch の網羅のため。
        };
        if (admins == null) {
            return List.of();
        }
        return admins.stream()
                .filter(Objects::nonNull)
                .filter(userId -> !userId.equals(oldPayerUserId))
                .distinct()
                .toList();
    }

    /**
     * 指定ユーザーが当該スコープの引継先候補（＝承諾できる他 ADMIN）かを判定する（設計書 §5.6）。
     */
    public boolean isEligibleAcceptor(
            EntitlementScopeKind scopeKind, Long scopeId, Long oldPayerUserId, Long operatorUserId) {

        if (operatorUserId == null) {
            return false;
        }
        return candidateAdminUserIds(scopeKind, scopeId, oldPayerUserId).contains(operatorUserId);
    }
}
