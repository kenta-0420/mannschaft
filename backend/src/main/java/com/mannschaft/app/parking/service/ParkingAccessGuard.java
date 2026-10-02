package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 駐車場（F09.3）ドメインの認可ガード（認可根治戦役 Wave5）。
 *
 * <p>本ドメインの scope 系 Controller（組織/チーム × 申請・譲渡希望・サブリース・来場者予約・ウォッチリスト）は、
 * 区画 ID 束縛（{@link ParkingSpaceService#getSpaceIds}）に依存する一方で、呼び出し元での membership/ADMIN 検証を
 * 欠いていた。区画 ID 束縛は「他スコープの ID へ到達させない」効果はあるが、
 * <b>「そのスコープに所属しない任意ログインユーザーが自スコープ扱いで操作できてしまう」</b>という認可欠落
 * （BOLA/認可バイパス）は塞げていなかった。根因はドメイン全体に及ぶため、全 Controller の public 入口で
 * ここを経由して認可を敷く（{@code feedback_authz_gate_on_public_entry_not_shared_method}:
 * 認可ガードは public 入口に集約し、バッチ等と共有される内部 finder には敷かない）。</p>
 *
 * <h3>二段防御</h3>
 * <ul>
 *   <li><b>一段目（本ガード）</b>: URL パスの scope（organizationId / teamId）に対する membership/ADMIN 検証。
 *       非メンバーは 403（COMMON_002）。</li>
 *   <li><b>二段目（既存の scope 束縛）</b>: {@link ParkingSpaceService#getSpaceIds} で得た
 *       スコープ内 spaceId 群、および各 Service の {@code findByIdAndSpaceIdIn} /
 *       {@code findByIdAndScopeTypeAndScopeId} が、対象 entity が URL パスの scope に属することを保証し、
 *       越境 ID は 404（{@code PARKING_004/005/006/025} 等）で存在秘匿する。</li>
 * </ul>
 * これにより「非メンバーの侵入（一段目 403）」と「メンバーによる越境 ID 参照（二段目 404）」の双方を塞ぐ。
 *
 * <h3>粒度</h3>
 * <ul>
 *   <li>read（一覧/詳細/空き状況/決済参照）＝ {@link #requireScopeMember}（非メンバー 403）。</li>
 *   <li>write/manage（承認/却下/抽選/譲渡確定/サブリース更新・削除・承認・終了/来場者承認・チェックイン・完了）＝
 *       {@link #requireScopeAdmin}（非 ADMIN 403）。</li>
 *   <li>自己申請・自己保有系（区画申請・譲渡/サブリース申込・来場者予約作成・ウォッチリスト・定期テンプレート・
 *       自分の申請/予約の取消）＝ {@link #requireScopeMember}。本人性の最終判定は各 Service の
 *       {@code checkOwnerOrAdmin} / {@code listedBy} 一致 / {@code findByIdAndUserId...} が担う
 *       （正当な本人操作を温存）。</li>
 * </ul>
 *
 * <p>区画 CRUD（{@code Org/TeamParkingSpaceController}）は {@link ParkingSpaceService} 自身が
 * entity 由来 scope で {@code checkMembership}/{@code checkAdminOrAbove} を敷済み（Wave2 2B）のため、
 * 本ガードの二重敷設対象外とする。{@code VehicleController}/{@code StripeConnectController} は
 * {@code /users/me/**} の純自己リソース（scope を持たない）ゆえ本ガード非適用。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 指定スコープ（organizationId / teamId）のメンバーであることを要求する（read・自己申請系の入口）。
     * 非メンバーは 403（COMMON_002）。
     *
     * @param scopeType {@code "ORGANIZATION"} または {@code "TEAM"}
     * @param scopeId   組織 ID またはチーム ID（URL パス由来）
     * @param userId    操作ユーザー ID
     */
    public void requireScopeMember(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType);
    }

    /**
     * 指定スコープ（organizationId / teamId）の ADMIN/DEPUTY_ADMIN であることを要求する（write/manage の入口）。
     * 非 ADMIN は 403（COMMON_002）。
     *
     * @param scopeType {@code "ORGANIZATION"} または {@code "TEAM"}
     * @param scopeId   組織 ID またはチーム ID（URL パス由来）
     * @param userId    操作ユーザー ID
     */
    public void requireScopeAdmin(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
    }
}
