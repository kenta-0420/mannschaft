package com.mannschaft.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * スケジュールタスク有効化設定。
 *
 * <p>{@link ShedLockConfig} と組み合わせて、分散環境でも1インスタンスのみ実行を保証する。</p>
 * <p>openapi-gen プロファイルでは無効（DB 接続不要の軽量起動のため）。</p>
 */
@Configuration
@Profile("!openapi-gen")
@EnableScheduling
public class SchedulingConfig {}
