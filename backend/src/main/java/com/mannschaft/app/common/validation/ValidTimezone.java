package com.mannschaft.app.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * IANA タイムゾーン名（例: {@code Asia/Tokyo}・{@code Asia/Kolkata}）であることを課す制約（Issue #2487 項目 4）。
 *
 * <h2>なぜ必要か</h2>
 * <p>{@code users.timezone} は無検証だったため、プロフィール更新 API / 新規登録 API から
 * <b>任意の文字列</b>（{@code "Foo/Bar"} 等）をそのまま保存できた。消費側は軒並み
 * {@code ZoneId.of()} を try-catch して既定値へフォールバックするので即死はしないが、
 * <b>ユーザー本人には理由の分からないまま日付境界が JST に落ちる</b>（活動日数・当日判定・
 * リマインダー時刻が本人の設定と食い違う）。壊れた値をフォールバックで覆い隠すのではなく、
 * <b>入口で弾いて 400 を返す</b>のが根治である（CLAUDE.md 障害対応の原則）。</p>
 *
 * <h2>検証内容</h2>
 * <p>{@code null} は「未指定＝更新しない」を意味するため通す（必須にしたい場合は
 * {@code @NotNull} と併用すること）。非 null の値は {@code ZoneId} として解決でき、かつ
 * <b>実在する IANA ゾーン名である</b>ことを要求する（{@code "+09:00"} のような固定オフセット表記や
 * {@code "Z"} は拒否する。夏時間を持つ地域で恒久的にずれるため）。</p>
 *
 * @see TimezoneValidator
 */
@Documented
@Constraint(validatedBy = TimezoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimezone {

    /** エラーメッセージ。 */
    String message() default "タイムゾーンは IANA タイムゾーン名（例: Asia/Tokyo）で指定してください";

    /** バリデーショングループ。 */
    Class<?>[] groups() default {};

    /** ペイロード。 */
    Class<? extends Payload>[] payload() default {};
}
