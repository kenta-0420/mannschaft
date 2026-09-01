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
 *   <li>承継ロジックは §11.2 の優先順位・候補資格をそのまま適用する（{@code selectSuccessionCandidate}）</li>
 *   <li>昇格の実装経路は {@code AccountPurgedEvent} の issuer が存在しないため、
 *       {@code RoleSuccessionService#promoteForBatchSuccession}（{@code forceTransferForPurge}
 *       とは別メソッド）を使う</li>
 * </ul>
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
        corrected += processScopes("TEAM", userRoleRepository.findTeamIdsWithoutActiveAdmin());
        corrected += processScopes("ORGANIZATION", userRoleRepository.findOrganizationIdsWithoutActiveAdmin());
        return corrected;
    }

    private int processScopes(String scopeType, List<Long> scopeIds) {
        int corrected = 0;
        int promoted = 0;
        int archived = 0;
        int skipped = 0;
        int failed = 0;

        for (int offset = 0; offset < scopeIds.size(); offset += CHUNK_SIZE) {
            List<Long> chunk = scopeIds.subList(offset, Math.min(offset + CHUNK_SIZE, scopeIds.size()));
            for (Long scopeId : chunk) {
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
                    }
                } catch (Exception e) {
                    // AC9: 1スコープの失敗が他スコープ処理を止めない。
                    failed++;
                    log.error("ADMIN不在スコープ是正失敗: scopeType={}, scopeId={}", scopeType, scopeId, e);
                }
            }
        }

        log.info("ADMIN不在スコープ検出バッチ完了: scopeType={}, 対象={}件, 昇格={}件, 凍結={}件, スキップ={}件, 失敗={}件",
                scopeType, scopeIds.size(), promoted, archived, skipped, failed);
        return corrected;
    }
}
