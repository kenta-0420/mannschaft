package com.mannschaft.app.cms.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ予約公開バッチの<b>宣言</b>クラス（issue #2616・F06.1 §2210-2226）。
 *
 * <p>予約中の記事は {@code status = DRAFT} のまま {@code published_at} に未来時刻を持つ。
 * 本バッチが 1 分間隔で「公開時刻に達した予約記事」を拾い、{@code PUBLISHED} へ遷移させる。</p>
 *
 * <p><b>役割分担:</b> 本クラスはスケジュール宣言と「1 件ずつ回す・1 件の失敗を握って続行する」
 * 制御だけを持ち、対象抽出と実遷移は {@link BlogScheduledPublishService} に委譲する
 * （{@code ReservationPendingExpireBatchService} と同じ薄さ）。<b>本クラスには
 * {@code @Transactional} を付けない</b> — 全体を 1 tx で囲むと内側の失敗が participating tx を
 * rollback-only にマークし、行単位 try/catch が実質無効化されるため（委譲先 Javadoc 参照）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogScheduledPublishBatchService {

    private final BlogScheduledPublishService scheduledPublishService;
    /** 業務ローカル時刻の壁時計（{@code ClockConfig#wallClock}）。published_at と同一の時間基準。 */
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * 公開時刻に達した予約記事を {@code PUBLISHED} へ遷移させる。
     *
     * <p>1 件の失敗が他の記事を巻き込まないよう記事ごとに try/catch する。失敗は握り潰さず
     * {@code log.error} で記録し、次回起動で再試行される（遷移条件は時刻経過なので、
     * 失敗した記事は次回も対象に残る＝自己修復する）。</p>
     *
     * <p><b>「現在時刻」の時間基準:</b> {@code published_at} は {@code LocalDateTime.now()}
     * （<b>JVM 既定ゾーン基準の壁時計</b>）で書かれるため、判定も同じ基準で行う。
     * 既定の {@code Clock} Bean は UTC 固定（{@code ClockConfig#utcClock}）であり、そのまま比較すると
     * サーバ既定ゾーンが UTC でない環境（JST 等）でオフセット分ずれ、予約時刻より 9 時間早く/遅く
     * 公開される（{@code ReservationPendingExpireService#findExpirableUnits} の Javadoc に実測記録あり）。
     * そのため<b>壁時計 Bean {@code ClockConfig#wallClock} を明示的に注入</b>して用いる
     * （{@code TimeZoneConfig} が JVM 既定ゾーンに設定するのと同一プロパティ由来なので食い違わない）。</p>
     *
     * <p>戻り値はプリミティブ {@code int} ではなく参照型 {@code Integer}（issue #2724）。
     * ShedLock はプリミティブ戻り値のメソッドをロックできず {@code LockingNotSupportedException} で
     * 毎回失敗する（番人 {@code ScheduledBatchGuardTest} ルール 5）。</p>
     *
     * @return 公開した記事数。ShedLock がロックを取得できずスキップした場合は {@code null} になりうる
     */
    @BatchEndpoint(name = "blog-scheduled-publish",
            description = "公開時刻に達した予約公開ブログ記事(DRAFT＋未来published_at)を1分毎にPUBLISHEDへ遷移させる")
    @Scheduled(fixedDelay = 60_000)
    // lockAtMostFor は fixedDelay（1分）と同値にしない。同値だと 1 回の実行が 1 分を超えた瞬間に
    // ロックが失効し、次回起動と二重処理になる。1 回あたりの処理量は
    // BlogScheduledPublishService.MAX_POSTS_PER_RUN で上限化しつつ、ロック保持時間にも
    // 余裕（3 倍）を持たせて窓を閉じる。
    @SchedulerLock(name = "blogScheduledPublishBatch", lockAtLeastFor = "PT30S", lockAtMostFor = "PT3M")
    public Integer publishScheduledPosts() {
        LocalDateTime baseTime = LocalDateTime.now(wallClock);
        List<Long> duePostIds = scheduledPublishService.findDuePostIds(baseTime);
        if (duePostIds.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        int failedCount = 0;
        for (Long postId : duePostIds) {
            try {
                if (scheduledPublishService.publishScheduledPost(postId, baseTime)) {
                    publishedCount++;
                }
            } catch (Exception e) {
                failedCount++;
                log.error("ブログ予約公開に失敗（次回起動で再試行）: postId={}", postId, e);
            }
        }

        log.info("ブログ予約公開バッチ: 対象{}件中 {}件を公開、{}件が失敗",
                duePostIds.size(), publishedCount, failedCount);
        if (duePostIds.size() >= BlogScheduledPublishService.MAX_POSTS_PER_RUN) {
            log.info("ブログ予約公開バッチ: 上限{}件で打ち切った。残りは次回起動で処理する",
                    BlogScheduledPublishService.MAX_POSTS_PER_RUN);
        }
        return publishedCount;
    }
}
