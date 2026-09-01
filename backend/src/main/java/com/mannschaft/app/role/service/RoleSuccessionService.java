package com.mannschaft.app.role.service;

import com.mannschaft.app.role.dto.LastAdminScope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 柱①「ADMINゼロ根治」— purge（アカウント物理削除）例外経路限定の自動承継（案 C′）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11〜§13。
 * 通常のオーナー委譲（承諾型オファー・{@link OwnershipTransferOfferService} 相当）とは別系統。
 * purge 経路は退会者本人の30日タイムリミットに阻害されないよう、承諾を待たない
 * 「承諾スキップの強制委譲」を採る（§10.11 後半 / §11.1）。</p>
 *
 * <p>本クラスは骨格のみ。業務ロジックは出陣（実装フェーズ）で実装する。</p>
 */
@Service
public class RoleSuccessionService {

    /**
     * §11.2 の優先順位・候補資格に従って承継候補を選定する。
     *
     * <p>優先順位: ① 候補資格を満たす DEPUTY_ADMIN の最古参（{@code created_at} 昇順）
     * → ② 候補資格を満たす MEMBER の最古参 → 同一 {@code created_at} は ID 昇順でタイブレーク
     * → 候補ゼロなら空を返す（呼び出し元が archive へフォールバックする）。</p>
     *
     * <p>候補資格条件（AC6）: 現役在籍（除名予定・招待中は除外）／退会予定でない
     * （{@code deleted_at IS NULL} かつ {@code withdrawal_requested_at IS NULL}）／
     * 匿名化済・利用停止中でない。</p>
     *
     * @param scopeId   対象スコープ ID
     * @param scopeType TEAM / ORGANIZATION
     * @return 候補ユーザー ID。候補資格者が1人もいなければ {@link java.util.Optional#empty()}
     * TODO 出陣で実装（AC4, AC5, AC6）。
     */
    public java.util.Optional<Long> selectSuccessionCandidate(Long scopeId, String scopeType) {
        throw new UnsupportedOperationException("出陣で実装");
    }

    /**
     * 承諾スキップの強制委譲を実行する（purge 経路専用）。
     *
     * <p>通常の承諾型 {@code acceptOffer} とは別メソッドとして分離する（§10.11 後半）。
     * 監査ログに {@code forced=true} を記録し、昇格された利用者へ通知を発行する。
     * §12.6 のとおり、ロック取得後に候補資格を再検証してから実行する。</p>
     *
     * <p>AC7: {@code scopeType} ごとに独立実行し、TEAM の承継処理が同一ユーザーの
     * ORGANIZATION 側 {@code user_roles} 行を一切変更しないこと。</p>
     *
     * @param scopeId          対象スコープ ID
     * @param scopeType        TEAM / ORGANIZATION
     * @param withdrawingUserId 退会（purge）対象の旧 ADMIN ユーザー ID
     * @param purgeId          冪等キーの一部（AC12: scope + userId + purgeId で重複防止）
     * TODO 出陣で実装（AC4, AC7, AC12）。
     */
    public void forceTransferForPurge(Long scopeId, String scopeType, Long withdrawingUserId, UUID purgeId) {
        throw new UnsupportedOperationException("出陣で実装");
    }

    /**
     * §14 の退会受付ガード。他メンバー1人以上の lastAdmin スコープが残っていれば
     * {@code BusinessException(GdprErrorCode.GDPR_011)} を投げる（AC1）。
     * 他メンバー0人のスコープはブロックしない（purge 時 archive に委ねる、AC3）。
     *
     * @param userId 退会予定ユーザー ID
     * TODO 出陣で実装（AC1, AC2, AC3）。
     */
    public void checkNoLastAdminScopes(Long userId) {
        throw new UnsupportedOperationException("出陣で実装");
    }

    /**
     * {@code userId} が唯一の ADMIN であるスコープのうち、他メンバーが1人以上いるものだけを返す
     * （deletion-preview 表示用。§14）。
     *
     * @param userId 退会予定ユーザー ID
     * TODO 出陣で実装（AC1）。
     */
    public List<LastAdminScope> findBlockingLastAdminScopes(Long userId) {
        throw new UnsupportedOperationException("出陣で実装");
    }
}
