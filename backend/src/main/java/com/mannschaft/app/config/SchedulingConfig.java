package com.mannschaft.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * スケジュールタスク有効化設定。
 *
 * <p>{@link ShedLockConfig} と組み合わせて、分散環境でも1インスタンスのみ実行を保証する。</p>
 * <p>openapi-gen プロファイルでは無効（DB 接続不要の軽量起動のため）。</p>
 *
 * <p><b>test プロファイルでも無効</b>（{@link ShedLockConfig} と同じ {@code !test} 条件）。
 * かつては本クラスのみ {@code test} を含めており、統合テスト（{@code @ActiveProfiles("test")}）で
 * {@code @EnableScheduling} が効いたまま {@link ShedLockConfig} だけが無効になる非対称構成だった。
 * その結果、共有 Testcontainer を使う全統合テストの実行中に {@code @Scheduled} バッチが自動発火し:</p>
 * <ul>
 *   <li>{@code @SchedulerLock} 付きバッチ（例 {@code EmailOutboxWorker.poll}）が LockProvider 不在で
 *       {@code LockAssert.assertLocked()} → {@code IllegalStateException: The task is not locked} を投げ続ける</li>
 *   <li>{@code @SchedulerLock} 無しバッチ（例 {@code AdCampaignDeliveryWorker}・SYSTEM_ADMIN 通知集計）が
 *       共有コンテナへ FOR UPDATE ロック取得・大量クエリを反復し、コネクションプールとロックを圧迫する</li>
 * </ul>
 * <p>この背景負荷が、full-shard 実行時にのみ重い正常系ミューテーション（例 role 変更の
 * delete+flush+insert ＋ membership join ＋ REQUIRES_NEW 同期リスナ）を非決定的に 500 へ落としていた
 * （単独 shard では競合が無く緑）。スケジューラは本番でのみ動けばよく、統合テストでは自動発火させない
 * のが正しい（{@link ShedLockConfig} が既に {@code !test} で除外しているのと同一方針に揃える）。</p>
 */
@Configuration
@Profile("!test & !openapi-gen & !e2e")
@EnableScheduling
public class SchedulingConfig {}
