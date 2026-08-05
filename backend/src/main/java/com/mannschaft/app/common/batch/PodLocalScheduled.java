package com.mannschaft.app.common.batch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * この {@code @Scheduled} バッチが<b>意図的に Pod ローカルで動く</b>（分散排他
 * {@code @SchedulerLock} を敢えて付けない）ことを示す<b>監査済マーカー</b>。
 *
 * <h2>なぜこのマーカーが要るのか</h2>
 * <p>番人 {@code ScheduledBatchGuardTest} は、{@code @Scheduled} が付いたメソッドに
 * {@code @SchedulerLock} が併記されていないことを違反として検知する。複数 Pod で同時に
 * 走ると、同じ処理が Pod 数だけ重複実行される（＝二重通知・二重課金・二重集計）ためである。</p>
 *
 * <p>ところが<b>ロックを掛けるとかえって有害になるバッチ</b>が少数だけ存在する。
 * 典型は「各 Pod が自分のメモリ上に溜めたバッファを定期的に吐き出す」型の処理である。
 * この型にロックを掛けると、ロックを取れなかった Pod のバッファが<b>永久に flush されず
 * 溜まり続ける</b>（＝データ欠落とメモリ膨張）。すなわちこれらは
 * 「ロックの付け忘れ」ではなく「Pod ごとに走ることが設計そのもの」である。</p>
 *
 * <p>本注釈はその設計意図を<b>コード上に明示し、番人に監査済みとして承認させる</b>ための
 * マーカーである。付与すると当該メソッドは {@code @SchedulerLock} 必須ルールの対象外になる。</p>
 *
 * <h2>付与時の必須条件（すべて満たすこと）</h2>
 * <ol>
 *   <li><b>{@link #value()} に理由を書くこと</b>。「なぜ Pod ごとに走ってよいのか」ではなく
 *       「<b>なぜロックを掛けると有害なのか</b>」まで書き下すこと。理由なき付与は
 *       「ロック漏れの永久凍結」と区別がつかず、番人を骨抜きにするバックドアになるため厳禁
 *       （{@code IntentionallyPublic} と同じ規約思想）。</li>
 *   <li><b>付与対象メソッドに Javadoc を書くこと</b>。{@link #value()} は一行の要約であり、
 *       多重実行時に何が起きるか・冪等性がどう担保されているかは Javadoc に残す。</li>
 *   <li><b>{@code @Scheduled} が付いたメソッドにのみ付与すること</b>。他の場所に付けても
 *       番人は参照しないため、死んだ証跡になる。</li>
 * </ol>
 * <p>上記 1〜3 は二次番人 {@code BatchMarkerAnnotationGuardTest} が CI で機械的に検証する。</p>
 *
 * <h2>付与してはならない場合</h2>
 * <p>「とりあえず動けばよい」「ロックの付け方が分からない」は付与理由にならない。
 * 通知送出・課金・外部 API 呼び出し・集計値の書き込みを行うバッチは、
 * 多重実行が<b>そのままユーザーに見える害</b>になるため、必ず {@code @SchedulerLock} を付けること。</p>
 *
 * @see BatchEndpointExempt
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PodLocalScheduled {

    /**
     * Pod ローカル実行が正しい理由（<b>ロックを掛けると何が壊れるか</b>まで書くこと）。
     *
     * <p>文字列リテラルで直接記述すること（定数参照は、付与箇所を読んだだけでは根拠が
     * 追えないため二次番人が拒否する）。</p>
     *
     * @return 理由（空文字・空白のみ・実質のない一言は二次番人が拒否する）
     */
    String value();
}
