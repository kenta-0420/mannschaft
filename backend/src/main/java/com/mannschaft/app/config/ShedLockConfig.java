package com.mannschaft.app.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * ShedLock 設定。スケジュールタスクの分散排他制御を提供する（複数 pod 環境での
 * {@code @Scheduled} ジョブの重複起動を防ぐ）。openapi-gen プロファイルでは無効
 * （MySQL 不要の軽量起動のため）。
 *
 * <p>使用箇所（ロック名）一覧（一部抜粋。全件は {@code @SchedulerLock(name = ...)}
 * を grep して把握すること）:
 * <ul>
 *   <li>{@code authCleanupBatch} — F01.1 認証クリーンアップ</li>
 *   <li>{@code auditLogPartitionMaintenance} / {@code auditLogArchiveBatch} — F10.3 監査ログ</li>
 *   <li>{@code monthlyKpiSnapshot} / {@code dailyAnalyticsAggregation} — F11 分析バッチ</li>
 *   <li>{@code accountPurgeBatch} / {@code exportRecoveryBatch} — F10.7 GDPR</li>
 *   <li>{@code emergencyClosureReminderBatch} — F09.x 臨時休業リマインド</li>
 *   <li>{@code adFrequencyCapFlush} / {@code adCampaignDelivery} — F09.17 広告配信</li>
 *   <li>{@code adDailyStatsAggregation} — F09.19.3 広告日次集計（毎日 01:30）</li>
 *   <li>{@code adBannerReservationExpiry} — F09.19.3 予約 EXPIRED + FreqCap 返却（毎日 02:15）</li>
 *   <li>{@code corkboardAutoArchiveBatch} 他 — F09.8 コルクボード</li>
 *   <li>{@code chatMessageArchiveBatch} — F04.2 チャット archive</li>
 *   <li>{@code notificationCleanupBatch} — 通知保持バッチ（既読90日/未読365日を notifications_archive へ移送・毎日 04:00・lockAtMostFor=PT2H）</li>
 *   <li>{@code shift_auto_archive} / {@code shift_preference_reminder} — F08.7 シフト</li>
 *   <li>{@code emailOutboxWorker} — F09.18 メール配信ワーカー (lockAtMostFor=PT2M)</li>
 *   <li>{@code emailOutboxStuckRecovery} — F09.18 SENDING 残骸リカバリ (lockAtMostFor=PT5M)</li>
 *   <li>{@code todoDueReminderHourly} — F04.3 TODO期限リマインダー（毎時・TZ別送信）(lockAtLeastFor=PT50M)</li>
 *   <li>{@code reservationSlotGeneration} — F03.4.2 予約枠の horizon 差分を日次生成（毎日 0:15）</li>
 *   <li>{@code reservationReminderDispatchBatch} — F03.4 予約リマインド送出（1分間隔）</li>
 *   <li>{@code reservationWaitlistCleanupBatch} — F03.4.5 §6.1 キャンセル待ち失効クリーンアップ（毎日 0:45）</li>
 *   <li>{@code reservationPendingExpireBatch} — F03.4.5 §6.3 仮押さえ(PENDING)自動失効（5分間隔）</li>
 * </ul>
 *
 * <p>新しいバッチを追加する場合は本 Javadoc にロック名と一行説明を追記すること。
 *
 * <h2>この一覧はもう「唯一の防波堤」ではない（2026-08-05 / issue #2601）</h2>
 * <p>上記の一覧は長らく<b>人間の善意だけ</b>で維持されており、{@code @SchedulerLock} を
 * 付け忘れても CI は緑のまま、本番で二重実行が起きるまで誰も気づけなかった。
 * 現在は番人 {@code ScheduledBatchGuardTest}（{@code backend/src/test/java/.../common/architecture/}）が
 * 次の 3 点を CI で機械的に強制している:</p>
 * <ol>
 *   <li>{@code @Scheduled} には {@code @SchedulerLock} 必須
 *       （例外は {@code @PodLocalScheduled} を付けた監査済みのもののみ）</li>
 *   <li>{@code @SchedulerLock} には {@code lockAtMostFor} の明示必須
 *       （本クラスの {@code defaultLockAtMostFor = "30m"} への暗黙依存を禁止する。
 *       既定 30 分は数秒ワーカーには長すぎ、1 時間かかる夜間集計には短すぎるため、
 *       各バッチの最大実行時間を書き手に必ず考えさせる）</li>
 *   <li>{@code @Scheduled} には {@code @BatchEndpoint} 必須
 *       （例外は {@code @BatchEndpointExempt} を付けた監査済みのもののみ）</li>
 * </ol>
 * <p>したがって本 Javadoc の一覧は<b>運用者が全体像を俯瞰するための地図</b>であって、
 * 規約の強制手段ではない。追記を忘れても番人は落ちないが、
 * 逆に番人を通らないバッチはそもそもマージできない。詳細は
 * {@code backend/.claudecode.md} §30.1 / {@code TEST_CONVENTION.md} §9.6 を参照。</p>
 */
@Configuration
@Profile("!test & !openapi-gen")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "30m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
