package com.mannschaft.app.common.backgroundgate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gate 基盤工事④-A: バックグラウンド入口の<b>停止時挙動の宣言</b>。
 *
 * <p>①公開フラグ読取 API・②FE route ガード・③{@code @RequireFeature} による API ゲートは、
 * いずれも<b>人間の入口</b>しか閉じない。バッチと Spring イベントリスナーは
 * 入口を閉じた後も裏で動き続けるため、未公開機能が通知を飛ばし・課金を動かし・
 * 集計を書き換えるという穴が残る。本アノテーションはその穴を宣言で塞ぐ。</p>
 *
 * <h2>付与位置はメソッドのみ</h2>
 * <p>{@link ElementType#METHOD} だけを対象とする。クラスレベルを許すと、
 * 将来そのクラスに足されたメソッドが<b>暗黙に宣言済み</b>になり、
 * 「宣言し忘れ」と「宣言した上での ALWAYS」の区別がコードから消えるためである
 * （金型: {@code RequireFeatureInterfaceGuardTest} の付与位置固定と同じ思想）。</p>
 *
 * <h2>キーの系統</h2>
 * <p>{@link #gateKeys()} に書くのは {@code feature_flags.flag_key}
 * （SCREAMING_SNAKE、例 {@code FEATURE_SHIFT_ENABLED}）であり、棚卸し台帳
 * {@code docs/inventory/feature-inventory.yaml} の {@code release.gate_key} と同一文字列である。
 * <b>台帳と seed の積集合に実在するリテラル</b>のみ許される
 * （番人 {@code BackgroundFeaturePolicyAnnotationGuardTest} が CI で機械的に検証する）。</p>
 *
 * <p>試練の骨格。実際のゲート挙動は出陣で {@link BackgroundFeaturePolicyAspect} に実装する。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BackgroundFeaturePolicy {

    /**
     * 停止時挙動。
     *
     * <p>モードと付与先の対応（番人が強制する）:</p>
     * <ul>
     *   <li>{@link BackgroundFeatureMode#SKIP_WHEN_DISABLED} — {@code @Scheduled} 付きメソッド専用</li>
     *   <li>{@link BackgroundFeatureMode#DROP_WHEN_DISABLED} —
     *       {@code @EventListener} / {@code @TransactionalEventListener} 付きメソッド専用</li>
     *   <li>{@code @SqsListener} — {@link BackgroundFeatureMode#ALWAYS} のみ許可。
     *       正常終了すると SQS メッセージが ACK され<b>復旧不能な消失</b>になるため、
     *       スキップもドロップも選べない。</li>
     * </ul>
     */
    BackgroundFeatureMode mode();

    /**
     * 判定に用いるフィーチャーフラグキー（{@code feature_flags.flag_key}）。
     *
     * <p>複数指定した場合は <b>AND</b>（1 つでも無効ならスキップ／ドロップ）。
     * 行が無い未知キーは {@code FeatureFlagService#isEnabled} が false を返すため
     * <b>フェイルクローズ</b>（＝無効扱い）になる。</p>
     *
     * <p>{@link BackgroundFeatureMode#ALWAYS} では指定禁止。</p>
     */
    String[] gateKeys() default {};

    /**
     * この宣言を選んだ根拠。文字列リテラル必須・空白のみ不可・最小文字数あり。
     *
     * <p>{@link BackgroundFeatureMode#ALWAYS} なら「止めると何の整合性が壊れるか」、
     * {@link BackgroundFeatureMode#DROP_WHEN_DISABLED} なら
     * <b>イベントが再生されず失われること</b>を許容できる根拠を書くこと。</p>
     */
    String reason();
}
