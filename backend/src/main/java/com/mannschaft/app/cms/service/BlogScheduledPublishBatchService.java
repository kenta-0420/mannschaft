package com.mannschaft.app.cms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ブログ予約公開バッチの<b>宣言</b>クラス（issue #2616・F06.1 §2210-2226）。
 *
 * <p><b>本クラスは試練（テスト先行）が置いた空シグネチャである。</b>
 * 中身とアノテーション（{@code @BatchEndpoint} / {@code @Scheduled} / {@code @SchedulerLock}）は
 * 出陣（実装）で付けること。番人テスト {@code BlogScheduledPublishBatchGuardTest} が
 * 期待する宣言を固定している。</p>
 *
 * <h2>出陣が満たすべき宣言（AC-14）</h2>
 * <ul>
 *   <li>{@code @BatchEndpoint(name = "blog-scheduled-publish", description = "...")}</li>
 *   <li>{@code @Scheduled(fixedDelay = 60_000)}（1 分間隔）</li>
 *   <li>{@code @SchedulerLock(name = "blogScheduledPublishBatch", lockAtLeastFor = "PT30S",
 *       lockAtMostFor = "PT3M")}（起動間隔の 3 倍。同値だと二重処理の窓が開く）</li>
 *   <li>戻り値は<b>プリミティブ厳禁</b>。ShedLock がプリミティブ戻り値をロックできず
 *       {@code LockingNotSupportedException} で毎回失敗するため {@code Integer} を返す
 *       （{@code ScheduledBatchGuardTest} ルール 5）</li>
 *   <li>本クラスに {@code @Transactional} を<b>付けない</b>。全体を 1 tx で囲むと内側の失敗が
 *       rollback-only をマークし、行単位 try/catch が実質無効化される</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogScheduledPublishBatchService {

    private final BlogScheduledPublishService scheduledPublishService;

    /**
     * 公開時刻に達した予約記事を {@code PUBLISHED} へ遷移させる。
     *
     * @return 公開した記事数。ShedLock がロックを取得できずスキップした場合は {@code null} になりうる
     */
    public Integer publishScheduledPosts() {
        throw new UnsupportedOperationException("未実装（試練・issue #2616）");
    }
}
