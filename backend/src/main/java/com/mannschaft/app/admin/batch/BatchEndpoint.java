package com.mannschaft.app.admin.batch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * F10.X 第一陣（バッチ実機検証基盤）— バッチエンドポイントを宣言するアノテーション。
 *
 * <p>{@link org.springframework.scheduling.annotation.Scheduled @Scheduled} が付与されたメソッドに併用し、
 * 当該バッチを「Mannschaft 内から名前で起動できる実機検証可能なエンドポイント」として登録する。
 * 起動時に {@link BatchEndpointRegistry} が全 Bean を走査して収集し、本アノテーションを介して
 * 統一的な前後処理（{@link BatchExecutionAspect} によるログ生成・イベント発火）が適用される。</p>
 *
 * <p>命名規約（{@link #name()}）:</p>
 * <pre>
 * {domain}-{action}[-{cadence}]
 *   例: village-serendipity-daily
 *       recruitment-auto-cancel
 *       pointcard-rematch-overnight
 * </pre>
 *
 * <p>同一 name を持つメソッドが複数登録されると {@link BatchEndpointRegistry} が起動時に
 * {@link IllegalStateException} を投げて FAIL FAST する。運用事故を起動時に検知するため意図的に
 * 厳格にしてある。</p>
 *
 * <p>第一陣時点では既存 75 バッチに本アノテーションは付与しない。第二陣以降で段階的に付与する。
 * 既存バッチには本 Aspect が一切影響しないため挙動は完全不変。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BatchEndpoint {

    /**
     * バッチエンドポイントの一意識別子（kebab-case）。
     *
     * <p>命名規約: {@code {domain}-{action}[-{cadence}]}</p>
     *
     * @return 識別子
     */
    String name();

    /**
     * 人間可読の説明（運用画面表示用）。任意。
     *
     * @return 説明文（未指定時は空文字列）
     */
    String description() default "";
}
