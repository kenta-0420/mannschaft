package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.service.PurgeMarkerService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import lombok.RequiredArgsConstructor;
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
 * <p>「開始マーク」の実体は {@code users.purge_started_at}（V197 で新設）。永続化は
 * D-3/D-5（越境 Repository 直接依存禁止）に従い、auth ドメインの狭い窓口
 * {@link PurgeMarkerService} 経由で行う（{@code UserRowLockService} と同じパターン）。
 * {@code markPurgeStarted} は {@code REQUIRES_NEW} で独立コミットする ──
 * {@code AccountPurgeService#purgeUser} は同一トランザクション内で最終的にユーザー行を
 * 物理削除するため、マークが本体と同じトランザクションのままだと他トランザクションから
 * 中間状態として観測できない（コミットまで見えない）。マークだけを先に確定させることで、
 * purge 本体が失敗・ロールバックしても「purge を試みた」記録が残り、cancel を確実に止める
 * （安全側に倒す設計）。</p>
 */
@Service
@RequiredArgsConstructor
public class PurgeStartGuard {

    private final PurgeMarkerService purgeMarkerService;

    /** purge 開始マークを冪等に記録する（既にマーク済みなら何もしない）。 */
    public void markPurgeStarted(Long userId) {
        purgeMarkerService.markPurgeStarted(userId);
    }

    /** purge 開始マーク済みなら true。 */
    public boolean isPurgeStarted(Long userId) {
        return purgeMarkerService.isPurgeStarted(userId);
    }

    /**
     * cancel-withdrawal 実行前に呼ぶ。purge 開始マーク済みなら
     * {@code BusinessException(GdprErrorCode.GDPR_012)} を投げる（409）。
     */
    public void checkCancelAllowed(Long userId) {
        if (isPurgeStarted(userId)) {
            throw new BusinessException(GdprErrorCode.GDPR_012);
        }
    }
}
