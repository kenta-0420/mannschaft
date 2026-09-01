package com.mannschaft.app.role.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleSuccessionService;
import com.mannschaft.app.role.service.RoleSuccessionService.BatchSuccessionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 柱①「ADMINゼロ根治」§13 — 既存データ検出バッチ。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §12.8 / §13。
 * 村ドメイン {@code VillageHeadmanSuccessionBatchService}（F17.1）を踏襲した実装とする。</p>
 *
 * <ul>
 *   <li>夜次実行、チャンクサイズ 500、ShedLock 排他、手動発火可</li>
 *   <li>§12 のスコープロックを同じ手順で通す（{@code RoleSuccessionService#promoteForBatchSuccession}
 *       が既存 {@code AdminRoleMutationLockService} 経由でロックする。バッチ専用の別ロック経路は作らない）</li>
 *   <li>承継ロジックは §11.2 の優先順位・候補資格をそのまま適用する（{@code selectTopCandidates}）</li>
 *   <li>昇格の実装経路は {@code AccountPurgedEvent} の issuer が存在しないため、
 *       {@code RoleSuccessionService#promoteForBatchSuccession}（{@code forceTransferForPurge}
 *       とは別メソッド）を使う</li>
 * </ul>
 *
 * <p>検分反映（P2-1）: スコープ ID の取得を「全件 List → Java 側 subList 分割」から
 * DB 側の {@code id} keyset ページング（{@code WHERE id > :afterId ... LIMIT}）へ変更した。
 * 大規模化時に全件をヒープへ展開しない。</p>
 *
 * <p>各スコープの是正（{@code RoleSuccessionService#promoteForBatchSuccession}）は
 * {@code @Transactional}（デフォルト伝播）であり、本クラスの {@code run()} 自体は
 * トランザクション境界を持たないため、呼び出しごとに独立した新規トランザクションになる
 * （P1-1 のような rollback-only 巻き添えは構造的に発生しない）。</p>
 *
 * <p>AC9: バッチ処理対象時点で active スコープの ADMIN 数 0 が 0 件になること
 * （昇格 or archive）。1スコープの失敗が他スコープ処理を止めないこと。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminlessScopeSuccessionBatchService {

    private static final int CHUNK_SIZE = 500;

    private final UserRoleRepository userRoleRepository;
    private final RoleSuccessionService roleSuccessionService;

    /**
     * 毎日 UTC 03:00 にバッチ実行する（村ドメイン {@code VillageHeadmanSuccessionBatchService} と同一時刻）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "ADMIN不在スコープの検出・是正であり、止めると組織/チームの管理不能状態が放置される。"
                    + "対応する gate_key が無いため常時実行する")
    @BatchEndpoint(name = "adminless-scope-succession-daily", description = "ADMIN不在スコープを検出し夜次で是正する")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(
            name = "adminlessScopeSuccessionBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        run();
    }

    /**
     * 全スコープを巡回し、ADMIN 不在（ADMIN 数 0）のスコープを検出・是正する。
     *
     * <p>手動発火可（運用者が緊急是正するための公開エントリポイント）。</p>
     *
     * @return 是正件数（昇格 + archive の合計）
     */
    public int run() {
        int corrected = 0;
        corrected += processScopeType("TEAM", userRoleRepository::findTeamIdsWithoutActiveAdminPage);
        corrected += processScopeType("ORGANIZATION", userRoleRepository::findOrganizationIdsWithoutActiveAdminPage);
        return corrected;
    }

    /** ページ供給関数。{@code (afterId, pageSize) -> id昇順の次ページ}。 */
    @FunctionalInterface
    private interface PageFetcher {
        List<Long> fetch(long afterId, int pageSize);
    }

    private int processScopeType(String scopeType, PageFetcher pageFetcher) {
        int corrected = 0;
        int promoted = 0;
        int archived = 0;
        int skipped = 0;
        int retryLater = 0;
        int failed = 0;
        int total = 0;

        long afterId = 0L;
        while (true) {
            List<Long> page = pageFetcher.fetch(afterId, CHUNK_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (Long scopeId : page) {
                total++;
                try {
                    BatchSuccessionResult result = roleSuccessionService.promoteForBatchSuccession(scopeId, scopeType);
                    switch (result) {
                        case PROMOTED -> {
                            promoted++;
                            corrected++;
                        }
                        case ARCHIVED -> {
                            archived++;
                            corrected++;
                        }
                        case NOT_NEEDED -> skipped++;
                        // Codex第3巡P1: 上位候補が全滅したが他に候補が存在する可能性がある場合、
                        // 本トランザクションでは是正せず次回バッチ実行に委ねる（archive しない）。
                        case RETRY_LATER -> retryLater++;
                    }
                } catch (Exception e) {
                    // AC9: 1スコープの失敗が他スコープ処理を止めない。
                    failed++;
                    log.error("ADMIN不在スコープ是正失敗: scopeType={}, scopeId={}", scopeType, scopeId, e);
                }
            }
            afterId = page.get(page.size() - 1);
            if (page.size() < CHUNK_SIZE) {
                break;
            }
        }

        log.info("ADMIN不在スコープ検出バッチ完了: scopeType={}, 対象={}件, 昇格={}件, 凍結={}件, "
                        + "スキップ={}件, 次回再試行={}件, 失敗={}件",
                scopeType, total, promoted, archived, skipped, retryLater, failed);
        return corrected;
    }
}
