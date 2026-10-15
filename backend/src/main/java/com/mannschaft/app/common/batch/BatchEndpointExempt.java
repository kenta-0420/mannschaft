package com.mannschaft.app.common.batch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * この {@code @Scheduled} バッチを<b>意図的にバッチ実行履歴基盤へ登録しない</b>
 * （{@code @BatchEndpoint} を敢えて付けない）ことを示す<b>監査済マーカー</b>。
 *
 * <h2>なぜこのマーカーが要るのか</h2>
 * <p>番人 {@code ScheduledBatchGuardTest} は、{@code @Scheduled} が付いたメソッドに
 * {@code @BatchEndpoint} が併記されていないことを違反として検知する。
 * {@code @BatchEndpoint} は当該バッチを「名前で起動でき、実行ログが残る実機検証可能な
 * エンドポイント」として登録するものであり、運用上ほぼすべてのバッチに必要である。</p>
 *
 * <p>ただし<b>数秒間隔で回る高頻度ワーカー</b>は例外になりうる。これらは 1 日あたり
 * 数万回起動されるため、1 回ごとに実行履歴を書くと履歴テーブルが「何もしなかった記録」で
 * 埋まり、<b>本来見たい日次・月次バッチの記録が埋没して履歴基盤自体が無意味になる</b>。
 * 加えて履歴書き込み自体がワーカーの主処理より重くなりうる。</p>
 *
 * <p>本注釈はその設計意図を明示し、番人に監査済みとして承認させるためのマーカーである。
 * 付与すると当該メソッドは {@code @BatchEndpoint} 必須ルールの対象外になる。</p>
 *
 * <h2>付与時の必須条件（すべて満たすこと）</h2>
 * <ol>
 *   <li><b>{@link #value()} に理由を書くこと</b>。「起動間隔」と「履歴を書くと何が壊れるか」を
 *       具体的に書き下すこと。理由なき付与は「実装漏れの永久凍結」と区別がつかない。</li>
 *   <li><b>付与対象メソッドに Javadoc を書くこと</b>（代替の可観測性——メトリクス・
 *       サマリログ等——をどこで担保しているかを残す）。</li>
 *   <li><b>{@code @Scheduled} が付いたメソッドにのみ付与すること</b>。</li>
 * </ol>
 * <p>上記 1〜3 は二次番人 {@code BatchMarkerAnnotationGuardTest} が CI で機械的に検証する。</p>
 *
 * <h2>付与してはならない場合</h2>
 * <p>日次・週次・月次のバッチは、起動回数が少なく履歴が埋没しないため対象外である。
 * 「まだ付けていないだけ」を本注釈で黙らせてはならない。</p>
 *
 * @see PodLocalScheduled
 * @see com.mannschaft.app.admin.batch.BatchEndpoint
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BatchEndpointExempt {

    /**
     * バッチ実行履歴基盤へ登録しない理由（<b>起動間隔と、履歴を書いた場合の害</b>を書くこと）。
     *
     * <p>文字列リテラルで直接記述すること（定数参照は二次番人が拒否する）。</p>
     *
     * @return 理由（空文字・空白のみ・実質のない一言は二次番人が拒否する）
     */
    String value();
}
