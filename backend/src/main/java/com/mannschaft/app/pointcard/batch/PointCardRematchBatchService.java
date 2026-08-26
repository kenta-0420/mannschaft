package com.mannschaft.app.pointcard.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.pointcard.service.ProviderMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F18 Phase 5 P5-S4 — {@code provider_id IS NULL} カードの夜間再マッチバッチ。
 *
 * <p>カード初回登録時の fuzzy match で運営プロバイダーマスタにヒットしなかったカード
 * （= {@code provider_id IS NULL} の「自由入力カード」）を、毎晩 03:00 (Asia/Tokyo) に
 * 再評価する。プロバイダーマスタへの新規追加・シノニム辞書編集（Phase 4 P4-S3）の効果を
 * 既存カードに遡及反映するための補助バッチである。
 *
 * <h2>背景</h2>
 * <p>{@link ProviderMatchService} は起動時とマスタ更新時（{@code ProviderCacheRefreshEvent}）に
 * キャッシュを再構築するため新規登録カードへは即時反映されるが、既に保存済みの
 * {@code provider_id=NULL} レコードは UPDATE されない。バッチで再走することで
 * 「過去に〇〇ポイントと自由入力したカードが、運営マスタ追加後に自動でロゴ付きになる」体験を実現する。
 *
 * <h2>設計判断（マスター御裁可済み）</h2>
 * <ul>
 *   <li><strong>cron 03:00 (Asia/Tokyo)</strong>: F15.4 の 02:00 集計バッチと被らない時刻を選定</li>
 *   <li><strong>chunk-size 1000</strong>: メモリ圧迫回避 + 1 トランザクション粒度。
 *       1 チャンクごとに {@link Propagation#REQUIRES_NEW} で別トランザクションに切る</li>
 *   <li><strong>個別失敗スキップ続行</strong>: 1 件の例外でバッチ全体を倒さない</li>
 *   <li><strong>集計監査ログ 1 件のみ</strong>: 1 万件 UPDATE しても監査ログ爆発を起こさない</li>
 *   <li><strong>失敗率 10% 超で Sentry HIGH 通知</strong>: 暗号化復号失敗や DB 障害の検知</li>
 * </ul>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>CLAUDE.md 原則 5: pointcard ドメイン内のみで完結（@Transactional 越境なし）</li>
 *   <li>{@link SchedulerLock} で複数インスタンス同時起動を防止
 *       （ShedLock v6.2.0 + V11.010 shedlock テーブル）</li>
 *   <li>{@link ConditionalOnProperty} で local 環境などに Bean 登録回避経路を残す</li>
 * </ul>
 *
 * <h2>設計書</h2>
 * <p>{@code docs/features/F18_point_card_wallet.md} §15 Phase 5 / §16 残課題 / §11 監査ログ</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "pointcard.rematch-batch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PointCardRematchBatchService {

    /** ShedLock のジョブ名（テスト・運用ログから参照されるため定数化）。 */
    static final String JOB_NAME = "pointCardRematchBatch";

    /** 高失敗率 Sentry 通知の閾値（10%）。 */
    static final double HIGH_FAILURE_RATIO = 0.10;

    private final UserPointCardRepository userPointCardRepository;
    private final ProviderMatchService providerMatchService;
    private final AuditLogService auditLogService;
    private final ErrorReportService errorReportService;

    @Value("${pointcard.rematch-batch.chunk-size:1000}")
    private int chunkSize;

    /**
     * 毎晩 03:00 (Asia/Tokyo) に起動するエントリポイント。
     *
     * <p>{@link Transactional} は付けず、チャンク単位で {@link #processChunk(Pageable)} に
     * {@link Propagation#REQUIRES_NEW} で別トランザクションを切る。これにより
     * 一括 rollback / 長時間ロック / メモリ圧迫を回避する。
     *
     * <p>処理終了後、件数集計を {@link AuditEventType#POINT_CARD_REMATCH_BATCH_EXECUTED}
     * 監査ログに 1 件のみ記録する。失敗率が 10% を超えた場合は
     * {@link ErrorReportService#recordBackendException} で Sentry HIGH 通知を投げる。
     */
    @Scheduled(cron = "${pointcard.rematch-batch.cron:0 0 3 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = JOB_NAME,
            lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = "pointcard-rematch",
            description = "プロバイダー未確定(provider_id NULL)のポイントカードをマスタ・シノニム辞書に照らして毎晩03:00に再マッチングする")
    public void execute() {
        long startedAt = System.currentTimeMillis();
        log.info("[PointCardRematchBatch] 再マッチバッチ開始: chunkSize={}", chunkSize);

        int total = 0;
        int matched = 0;
        int skipped = 0;

        int pageIndex = 0;
        while (true) {
            Pageable pageable = PageRequest.of(pageIndex, chunkSize);
            ChunkResult result;
            try {
                result = processChunk(pageable);
            } catch (RuntimeException e) {
                // チャンク全体が失敗した場合（DB ロック競合など）はバッチ全体を継続せず終了し、
                // Sentry 通知を投げる（DB 接続断のような致命傷を握りつぶさないため）
                log.error("[PointCardRematchBatch] チャンク処理が失敗しました: pageIndex={}", pageIndex, e);
                errorReportService.recordBackendException(e, null, ErrorReportSeverity.HIGH);
                break;
            }

            total += result.processed;
            matched += result.matched;
            skipped += result.skipped;

            if (result.lastPage) {
                break;
            }
            pageIndex++;
        }

        long durationMs = System.currentTimeMillis() - startedAt;

        // 失敗率（処理対象に対するスキップ件数の割合）を算出
        double failureRatio = total == 0 ? 0.0 : (double) skipped / (double) total;
        boolean highFailure = total > 0 && failureRatio > HIGH_FAILURE_RATIO;

        // 集計監査ログを 1 件発火（バッチ実行のため userId / teamId / orgId はすべて null）
        String metadata = String.format(
                "{\"total\":%d,\"matched\":%d,\"skipped\":%d,\"durationMs\":%d}",
                total, matched, skipped, durationMs);
        auditLogService.record(
                AuditEventType.POINT_CARD_REMATCH_BATCH_EXECUTED.name(),
                null, null, null, null,
                null, null, null,
                metadata);

        log.info("[PointCardRematchBatch] 再マッチバッチ完了: total={}, matched={}, skipped={}, durationMs={}",
                total, matched, skipped, durationMs);

        // 失敗率が閾値を超えていれば Sentry HIGH 通知（暗号化復号失敗の蓄積や DB 障害の早期検知）
        if (highFailure) {
            String message = String.format(
                    "PointCardRematchBatch 失敗率閾値超過: skipped=%d/total=%d (%.1f%%) > %.1f%%",
                    skipped, total, failureRatio * 100, HIGH_FAILURE_RATIO * 100);
            log.warn("[PointCardRematchBatch] {}", message);
            errorReportService.recordBackendException(
                    new PointCardRematchHighFailureException(message),
                    null,
                    ErrorReportSeverity.HIGH);
        }
    }

    /**
     * 1 チャンクを別トランザクションで処理する。
     *
     * <p>{@link Propagation#REQUIRES_NEW} を採用する理由:
     * <ul>
     *   <li>個別カードの暗号化復号失敗（{@code IllegalArgumentException}）が連鎖して
     *       バッチ全体をロールバックさせないため</li>
     *   <li>長時間トランザクションによるロック保持を避けるため</li>
     *   <li>失敗チャンクの影響を最小化（その 1 チャンクだけ脱落、次チャンクは継続）</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChunkResult processChunk(Pageable pageable) {
        Page<UserPointCardEntity> page = userPointCardRepository.findRematchTargets(pageable);
        List<UserPointCardEntity> targets = page.getContent();
        int processed = targets.size();
        int matched = 0;
        int skipped = 0;

        for (UserPointCardEntity card : targets) {
            UUID cardId = card.getId();
            try {
                String displayName = card.getDisplayName();
                Optional<PointCardProviderEntity> opt = providerMatchService.matchProvider(displayName);
                if (opt.isPresent()) {
                    card.setProviderId(opt.get().getId());
                    userPointCardRepository.save(card);
                    matched++;
                }
                // マッチしなかった場合は UPDATE 不要（依然 NULL のまま、次回の再マッチに賭ける）
            } catch (RuntimeException e) {
                // 個別カードの失敗（暗号化復号失敗 / 楽観ロック競合 / 永続化エラー）は
                // スキップ扱いとし、バッチを止めない
                skipped++;
                log.warn("[PointCardRematchBatch] 再マッチ失敗（スキップ）: cardId={}", cardId, e);
            }
        }

        return new ChunkResult(processed, matched, skipped, page.isLast());
    }

    /**
     * 1 チャンクの処理結果を保持する不変値オブジェクト。
     *
     * <p>{@code lastPage} は Spring Data の {@link Page#isLast()} 由来で、
     * これが {@code true} なら全件処理完了。
     */
    record ChunkResult(int processed, int matched, int skipped, boolean lastPage) {
    }

    /**
     * Sentry 通知用の専用例外。スタックトレース付きで {@code error_reports} に
     * 蓄積し、運用者が再マッチバッチの異常を即座にキャッチできるようにする。
     */
    static final class PointCardRematchHighFailureException extends RuntimeException {
        PointCardRematchHighFailureException(String message) {
            super(message);
        }
    }
}
