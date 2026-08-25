package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * バッチジョブログリポジトリ。
 */
public interface BatchJobLogRepository extends JpaRepository<BatchJobLogEntity, Long> {

    /**
     * ジョブ名で実行履歴を取得する。
     */
    List<BatchJobLogEntity> findByJobNameOrderByStartedAtDesc(String jobName);

    /**
     * ジョブ名で直近 1 件の実行履歴を取得する（F10.X 第二陣 — バッチキック API の status 表示用）。
     *
     * <h2>{@code started_at} だけで順序付けてはならない（Gate 基盤工事④-A / Codex 検分4巡目）</h2>
     * <p>{@code started_at} は {@code DATETIME} で<b>秒精度しか持たない</b>。手動実行と
     * スケジュール実行が重なると、通常実行行と後発の {@code SKIPPED} 行が同一秒になりうる。
     * このとき {@code ORDER BY started_at DESC} だけでは同着の解決が索引の物理順（PK 昇順）に
     * 委ねられ<b>古い方の行</b>が返るため、{@code BackgroundFeatureSkipRecorder} が
     * 「停止中なのに動いていた」と誤判定してスキップ行を重複記録し、
     * 管理 API も誤った直近状態を表示する。よって {@code id DESC} まで含めた<b>全順序</b>で引く。</p>
     *
     * <p>索引 {@code idx_bjl_job_started_id (job_name, started_at DESC, id DESC)} が
     * この順序を賄う（{@code V189}）。実測: filesort なし・実読み 1 行。</p>
     */
    Optional<BatchJobLogEntity> findFirstByJobNameOrderByStartedAtDescIdDesc(String jobName);

    /**
     * ステータス別にジョブログを取得する。
     */
    Page<BatchJobLogEntity> findByStatusOrderByStartedAtDesc(BatchJobStatus status, Pageable pageable);

    /**
     * 全ジョブログをページングで取得する。
     */
    Page<BatchJobLogEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
