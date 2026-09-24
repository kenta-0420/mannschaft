package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.repository.BatchJobLogRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code batch_job_logs} の「直近 1 行」が<b>全順序</b>で決まることの統合テスト
 * （Gate 基盤工事④-A / Codex 検分4巡目 P2）。
 *
 * <h2>なぜ実 DB で検証するのか</h2>
 * <p>これは Java 側のロジックではなく <b>ORDER BY 句が与える順序そのもの</b>の検証である。
 * mock や stub で「新しい方を返す」と決め打つと、検証しているのは stub の設定であって
 * クエリではない。同一秒の行が並んだときにどの行が返るかは DB の索引走査順が決めるため、
 * 実 MySQL（Testcontainers）に本物の行を並べて確かめる。</p>
 *
 * <h2>守る不変条件</h2>
 * <p>{@code started_at} は {@code DATETIME} で<b>秒精度しか持たない</b>。手動実行と
 * スケジュール実行が重なると、通常実行行と後発の {@code SKIPPED} 行が同一秒になりうる。
 * このとき {@code ORDER BY started_at DESC} だけでは同着の解決が索引の物理順（PK 昇順）に
 * 委ねられ、<b>古い方の行</b>が返る。すると:</p>
 * <ul>
 *   <li>{@code BackgroundFeatureSkipRecorder} が「停止中なのに動いていた」と誤判定し、
 *       スキップ行を重複記録する</li>
 *   <li>管理 API（{@code GET /{name}/status}）が誤った直近状態を表示する</li>
 * </ul>
 * <p>よって {@code started_at DESC, id DESC} の全順序で引かねばならない。</p>
 */
@DisplayName("batch_job_logs の直近1行は全順序で決まる（Gate基盤工事④-A / 同一秒の同着解決）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BatchJobLogLatestRowOrderingIT extends AbstractMySqlIntegrationTest {

    /** 他テストの行と混ざらないよう、本 IT 専用のジョブ名を使う。 */
    private static final String JOB = "gate4a-total-order-probe";

    /** 同一秒に複数行が並ぶ状況を作るための固定時刻（秒未満は DATETIME に保持されない）。 */
    private static final LocalDateTime SAME_SECOND = LocalDateTime.of(2026, 8, 22, 3, 0, 0);

    @Autowired
    private BatchJobLogService batchJobLogService;

    @Autowired
    private BatchJobLogRepository batchJobLogRepository;

    private BatchJobLogEntity save(BatchJobStatus status, LocalDateTime startedAt) {
        return batchJobLogRepository.saveAndFlush(BatchJobLogEntity.builder()
                .jobName(JOB)
                .status(status)
                .startedAt(startedAt)
                .build());
    }

    @Test
    @Transactional
    @DisplayName("同一秒に並んだ行のうち、後から挿入された行（id が大きい方）が直近1行として選ばれる")
    void 同一秒の同着では後発の行が選ばれる() {
        // given: 通常実行（SUCCESS）の直後、同じ秒にスキップ行が書かれた状況。
        //        手動実行とスケジュール実行が重なると実際に起こりうる並びである。
        BatchJobLogEntity older = save(BatchJobStatus.SUCCESS, SAME_SECOND);
        BatchJobLogEntity newer = save(BatchJobStatus.SKIPPED, SAME_SECOND);

        assertThat(newer.getId())
                .as("前提: 後から挿入した行の方が id が大きいこと（AUTO_INCREMENT）")
                .isGreaterThan(older.getId());
        assertThat(older.getStartedAt())
                .as("前提: 2 行の started_at が同一であること（秒精度で同着していること）")
                .isEqualTo(newer.getStartedAt());

        // when
        BatchJobLogEntity latest = batchJobLogService.findLatestByJobName(JOB).orElseThrow();

        // then
        assertThat(latest.getId())
                .as("started_at だけの順序では同着が索引の物理順（PK 昇順）で解決され、"
                        + "古い方の行が返る。停止中なのに「動いていた」と誤判定し、"
                        + "スキップ行を重複記録する原因になる")
                .isEqualTo(newer.getId());
        assertThat(latest.getStatus())
                .as("直近状態は SKIPPED（停止中）でなければならない")
                .isEqualTo(BatchJobStatus.SKIPPED);
    }

    @Test
    @Transactional
    @DisplayName("同一秒に3行以上並んでも、最後の行が選ばれる（同着解決が安定している）")
    void 同一秒に3行並んでも最後の行が選ばれる() {
        save(BatchJobStatus.SKIPPED, SAME_SECOND);
        save(BatchJobStatus.RESUMED, SAME_SECOND);
        BatchJobLogEntity last = save(BatchJobStatus.SKIPPED, SAME_SECOND);

        BatchJobLogEntity latest = batchJobLogService.findLatestByJobName(JOB).orElseThrow();

        assertThat(latest.getId()).isEqualTo(last.getId());
    }

    @Test
    @Transactional
    @DisplayName("秒が異なる場合は従来どおり started_at の新しい行が選ばれる（id の大小に引きずられない）")
    void 秒が異なる場合はstarted_atが優先される() {
        // 後から挿入した行（id が大きい）の方が started_at は古い、という並びを作る。
        // id だけで順序付ける実装に退化していれば、この検体だけが落ちる。
        BatchJobLogEntity newerByTime = save(BatchJobStatus.SKIPPED, SAME_SECOND.plusSeconds(60));
        BatchJobLogEntity olderByTime = save(BatchJobStatus.SUCCESS, SAME_SECOND);

        assertThat(olderByTime.getId()).isGreaterThan(newerByTime.getId());

        BatchJobLogEntity latest = batchJobLogService.findLatestByJobName(JOB).orElseThrow();

        assertThat(latest.getId())
                .as("第一キーはあくまで started_at である。id は同着の解決にのみ使う")
                .isEqualTo(newerByTime.getId());
    }
}
