package com.mannschaft.app.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

/**
 * {@code @Transactional} メソッドをデータソース（プライマリ / レプリカ）へ自動ルーティングする AOP。
 *
 * <p>{@code @Order(0)} により Spring の @Transactional アスペクト（Order=Integer.MAX_VALUE-1）より
 * 先に実行され、トランザクション開始前（＝物理コネクション取得前）にデータソース種別をセットできる。</p>
 *
 * <h2>ルーティング規則</h2>
 * <ul>
 *   <li>{@code readOnly = true} → {@link DataSourceType#REPLICA}（読み取りをレプリカへ逃がす）</li>
 *   <li>{@code readOnly = false}（書き込み）→ {@link DataSourceType#PRIMARY} を<b>明示的に</b>セットする</li>
 * </ul>
 *
 * <h2>なぜ書き込みで PRIMARY を「明示セット」するのか（根治ポイント）</h2>
 * <p>従来は書き込みメソッドで何もしなかったため、<b>readOnly な外側トランザクション配下から
 * 書き込みメソッドがネスト呼び出しされる</b>と、ThreadLocal が外側の {@code REPLICA} のまま残り、
 * ネスト側が {@code REQUIRES_NEW} で新規に張ったコネクションまで {@code REPLICA}（read-only）に
 * 流れて {@code "Connection is read-only"} で 500 になっていた。
 * 書き込みメソッド進入時に {@code PRIMARY} を明示セットすることで、{@code REQUIRES_NEW} で取得される
 * 新規コネクションが確実にプライマリへ向く。</p>
 *
 * <h2>なぜ finally で clear ではなく「復元」するのか</h2>
 * <p>ネストから復帰した際に外側の指定（例: 外側 readOnly の {@code REPLICA}）を失わないよう、
 * 進入前の値を退避し finally で復元する。トップレベル（進入前 null）では {@code clear()} 相当になる。
 * 単純な {@code clear()} は「ネスト復帰時に外側指定が消える」別の穴になり得るため採らない。</p>
 *
 * <p>ルーティングそのものは {@link RoutingDataSource#determineCurrentLookupKey()} が
 * <b>コネクション取得時</b>に本 ThreadLocal を参照して決定する。既に取得済みの同一トランザクション内で
 * ThreadLocal を変えても、そのコネクションの向き先は変わらない（＝新規トランザクション／
 * {@code REQUIRES_NEW} のときにのみ再評価される）点に注意。</p>
 *
 * <h2>ポイントカットの範囲（意図的に据え置き）</h2>
 * <p>ポイントカットは従来どおり<b>メソッドレベル</b>の {@code @Transactional}
 * （{@code @annotation}）に限定する。ここを {@code @within} 等でクラスレベルまで広げると、
 * クラスレベル {@code @Transactional(readOnly = true)} を持つ多数の
 * getOrCreate 系サービス（設定値の自動初期化で自己呼び出し INSERT を行うもの）が
 * 一斉にレプリカへルーティングされ、本件と同種の read-only 書き込みクラッシュを
 * 広範囲に顕在化させてしまう。ブラスト半径を抑えるため範囲は広げない。</p>
 */
@Aspect
@Component
@Order(0)
public class ReplicaRoutingAspect {

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object routeDataSource(ProceedingJoinPoint pjp) throws Throwable {
        Transactional transactional = resolveTransactional(pjp);

        // @Transactional が解決できない場合は既存のルーティング指定を尊重して素通しする。
        if (transactional == null) {
            return pjp.proceed();
        }

        DataSourceType previous = DataSourceContextHolder.getDataSourceType();
        DataSourceType target = transactional.readOnly() ? DataSourceType.REPLICA : DataSourceType.PRIMARY;
        DataSourceContextHolder.setDataSourceType(target);
        try {
            return pjp.proceed();
        } finally {
            // 進入前の値へ復元（トップレベルは previous=null のため clear 相当）
            if (previous != null) {
                DataSourceContextHolder.setDataSourceType(previous);
            } else {
                DataSourceContextHolder.clear();
            }
        }
    }

    /**
     * メソッドレベルの {@code @Transactional} を優先し、無ければクラスレベルを解決する。
     * （メソッドレベルがクラスレベルを上書きする Spring の解決規則と一致させる）
     */
    private Transactional resolveTransactional(ProceedingJoinPoint pjp) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Transactional transactional = method.getAnnotation(Transactional.class);
        if (transactional != null) {
            return transactional;
        }
        Object target = pjp.getTarget();
        if (target != null) {
            return target.getClass().getAnnotation(Transactional.class);
        }
        return null;
    }
}
