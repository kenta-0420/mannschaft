package com.mannschaft.app.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このエンドポイントが<b>意図的に無認可で公開されている</b>（{@code SecurityConfig} の
 * {@code permitAll()} 配下であり、認可を課さないことが設計上の意思決定である）ことを示す
 * <b>監査済マーカー</b>。
 *
 * <p>番人（{@code AuthzControllerGuardArchTest}）は認可シグナルを持たない Mapping メソッドを
 * 違反として検知するが、ヘルスチェック・公開 LP 用の参照 API・利用規約の取得など、
 * <b>誰でも読めてよいことが正しい仕様</b>であるエンドポイントも一定数存在する。
 * 本注釈はそれらを凍結ストアへ落とさず、<b>「認可漏れではなく意図的な公開である」と
 * 監査を経て明示承認する</b>ためのマーカーである。</p>
 *
 * <p><b>付与時の必須条件</b>（両方を満たすこと）:</p>
 * <ol>
 *   <li>根拠となる {@code SecurityConfig} の {@code permitAll()} 行を明記すること
 *       （例: {@code SecurityConfig.java:88 — requestMatchers("/api/v1/public/**").permitAll()}）</li>
 *   <li><b>公開してよいと判断した理由を必ず併記すること</b>
 *       （例: 「返却するのは全ユーザー共通のマスタ情報のみで、個人データ・テナント固有データを
 *       一切含まないため」）。理由なき付与は「認可漏れの永久凍結」と区別がつかず、
 *       番人を骨抜きにするバックドアになるため厳禁。</li>
 * </ol>
 *
 * <p><b>注意</b>: 本注釈は<b>認可が存在しないこと</b>を宣言するものであり、他の 2 マーカーとは
 * 意味が根本的に異なる。個人データ・テナント固有データを返す可能性が少しでもあるなら付与してはならない。
 * レスポンスに含まれる項目が将来増えた場合は公開の妥当性が崩れうるため、当該 DTO の変更時は
 * 必ず本注釈の妥当性を再評価すること。</p>
 *
 * <p>メソッドに付与するとその 1 エンドポイントのみ、クラスに付与するとそのクラスの
 * 全 Mapping メソッドが監査済みとして扱われる。</p>
 *
 * @see AuthorizedInService
 * @see AuthorizedByPathConfig
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IntentionallyPublic {
}
