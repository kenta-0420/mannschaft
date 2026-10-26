package com.mannschaft.app.repairplan.module;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * F08.8 修繕長期計画ダッシュボードの API メソッドに付与するマーカーアノテーション。
 *
 * <p>付与されたメソッドは {@link RepairPlanModuleGuardAspect} により実行前にガードされ、
 * 以下の 2 点を判定する:</p>
 * <ol>
 *   <li>対象スコープ（{@code scopeType} / {@code scopeId}）が
 *       {@code apartment} テンプレートを採用しているか</li>
 *   <li>{@code repair_longterm_plan} モジュールが有効化されているか</li>
 * </ol>
 *
 * <p>判定に失敗した場合、HTTP 422 ({@code REPAIR_PLAN_013} / {@code REPAIR_PLAN_014}) を返す。</p>
 *
 * <p>属性 {@link #scopeTypeParam()} / {@link #scopeIdParam()} で Aspect が解決対象とする
 * パラメータ名を指定する（既定値は {@code "scopeType"} / {@code "scopeId"}）。
 * Controller メソッドのパラメータ名は Spring 側で {@code -parameters} コンパイルオプションで
 * 保持される。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRepairPlanModule {

    /**
     * メソッド引数名: スコープ種別（{@code "ORGANIZATION"} / {@code "TEAM"}）。
     */
    String scopeTypeParam() default "scopeType";

    /**
     * メソッド引数名: スコープ ID。
     */
    String scopeIdParam() default "scopeId";
}
