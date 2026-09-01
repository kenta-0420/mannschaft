package com.mannschaft.app.gdpr.service;

import org.springframework.stereotype.Service;

/**
 * 柱①「ADMINゼロ根治」§12.5 — purge × cancel-withdrawal の勝敗を判定するガード。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12.5。</p>
 * <ul>
 *   <li>purge 開始マーク後は cancel-withdrawal を拒否する（409）</li>
 *   <li>purge 開始マーク前は cancel-withdrawal が勝つ（purge 自体が起動しない）</li>
 * </ul>
 *
 * <p>「開始マーク」の実体（{@code users.purge_started_at} 相当のカラム、または既存
 * {@code purged_at} の先行フラグ運用）は実装 PR で確定する（§12.5）。</p>
 *
 * <p>本クラスは骨格のみ。業務ロジックは出陣（実装フェーズ）で実装する。</p>
 */
@Service
public class PurgeStartGuard {

    /**
     * purge 開始マークを行う（冪等）。
     *
     * TODO 出陣で実装（AC11）。
     */
    public void markPurgeStarted(Long userId) {
        throw new UnsupportedOperationException("出陣で実装");
    }

    /**
     * purge 開始マーク済みなら true。
     *
     * TODO 出陣で実装（AC11）。
     */
    public boolean isPurgeStarted(Long userId) {
        throw new UnsupportedOperationException("出陣で実装");
    }

    /**
     * cancel-withdrawal 実行前に呼ぶ。purge 開始マーク済みなら
     * {@code BusinessException(GdprErrorCode.GDPR_011)} 相当の409を投げる。
     *
     * TODO 出陣で実装（AC11）。
     */
    public void checkCancelAllowed(Long userId) {
        throw new UnsupportedOperationException("出陣で実装");
    }
}
