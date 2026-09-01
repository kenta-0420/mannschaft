package com.mannschaft.app.role.batch;

import com.mannschaft.app.role.service.RoleSuccessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 柱①「ADMINゼロ根治」§13 — 既存データ検出バッチ。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12.8 / §13。
 * 村ドメイン {@code VillageHeadmanSuccessionBatchService}（F17.1）を踏襲した実装とする。</p>
 *
 * <ul>
 *   <li>夜次実行、チャンクサイズ 500、ShedLock 排他、手動発火可</li>
 *   <li>§12 のスコープロックを同じ手順で通す（バッチ専用の別ロック経路を作らない）</li>
 *   <li>承継ロジックは §11.2 の優先順位・候補資格をそのまま適用する</li>
 *   <li>昇格の実装経路は {@code AccountPurgedEvent} の issuer が存在しないため、
 *       {@code assignRole} を直接呼び {@code forced=true} 監査を記録する専用メソッド
 *       （既存 {@code forceTransferForPurge} とは別メソッド）を使う</li>
 * </ul>
 *
 * <p>AC9: バッチ処理対象時点で active スコープの ADMIN 数 0 が 0 件になること
 * （昇格 or archive）。1スコープの失敗が他スコープ処理を止めないこと。</p>
 *
 * <p>本クラスは骨格のみ。業務ロジックは出陣（実装フェーズ）で実装する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminlessScopeSuccessionBatchService {

    private final RoleSuccessionService roleSuccessionService;

    /**
     * 全スコープを巡回し、ADMIN 不在（ADMIN 数 0）のスコープを検出・是正する。
     *
     * @return 是正件数（昇格 + archive の合計）
     * TODO 出陣で実装（AC9）。
     */
    public int run() {
        throw new UnsupportedOperationException("出陣で実装");
    }
}
