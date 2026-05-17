package com.mannschaft.app.admin.batch;

import java.lang.reflect.Method;

/**
 * F10.X 第一陣 — {@link BatchEndpoint} 付きメソッドの登録情報。
 *
 * <p>{@link BatchEndpointRegistry} が起動時に収集し、名前による検索・呼び出しに使う。</p>
 *
 * @param name              バッチ識別子（{@link BatchEndpoint#name()}）
 * @param description       説明（{@link BatchEndpoint#description()}）
 * @param beanName          ターゲット Bean 名
 * @param method            実行対象メソッド（プロキシ越しに呼ぶための未加工 Method 参照）
 * @param schedulerLockName 同メソッドに付与された {@code @SchedulerLock} の name（無ければ NULL）
 */
public record BatchEndpointDescriptor(
        String name,
        String description,
        String beanName,
        Method method,
        String schedulerLockName) {
}
