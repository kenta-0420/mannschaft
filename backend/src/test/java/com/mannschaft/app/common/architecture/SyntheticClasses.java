package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;

/**
 * javac が生成する<b>合成クラス</b>（enum の網羅 {@code switch} から生まれる
 * {@code Foo$1} 等の {@code $SwitchMap} 保持クラス）を判定するユーティリティ。
 *
 * <h2>なぜ必要か</h2>
 * <p>境界番人（D-1 {@code CrossDomainEntityImportArchTest} 等）はクラス単位で
 * 依存関係を検査する。ドメイン越境 enum に対する網羅 {@code switch} を書くと、
 * javac は外側クラスとは<b>別名</b>の合成クラス {@code Foo$1} を生成し、これが
 * 越境先 enum への依存を持つ。外側クラスの依存が凍結済み（既存負債として台帳に
 * 記録済み）であっても、{@code Foo$1} は別クラスとして扱われるため
 * {@code FreezingArchRule} に「新規違反」と誤認され fail する
 * （実例: コミット {@code 598f56d09}、{@code NotificationService$1}）。
 *
 * <p>合成クラスの依存は外側クラスの依存の<b>写し</b>にすぎず、実質的に重複報告である。
 * そのため合成クラス自身は境界番人の検査対象から除外する。
 *
 * <h2>判定方法として {@link JavaModifier#SYNTHETIC} を採用した理由</h2>
 * <p>ArchUnit 1.3.0 の {@link JavaClass#getModifiers()} は
 * {@link com.tngtech.archunit.core.domain.properties.HasModifiers} 経由でクラスの
 * アクセスフラグ集合を返す。javac は enum の網羅 switch のために生成する
 * {@code $SwitchMap} 保持クラスに {@code ACC_SYNTHETIC} フラグを付与するため、
 * この修飾子の有無で合成クラスを確実に捕捉できる
 * （{@code CrossDomainEntityImportSyntheticClassExclusionTest} で実物のクラスファイル
 * を使って検証済み）。
 *
 * <p><b>採用しなかった判定方法</b>:
 * <ul>
 *   <li>クラス名に {@code $} を含むかどうか — Lombok の {@code @Builder} /
 *       {@code @SuperBuilder} が生成するネストクラス（例
 *       {@code ConfirmableNotificationEntity$ConfirmableNotificationEntityBuilder}、
 *       凍結ストアに現存する既存違反）も {@code $} を含むため、この条件では
 *       既存負債を静かに免罪してしまう（家の掟: 凍結は繰り延べであって免罪符ではない）。</li>
 *   <li>simple name が数字のみかどうか単独 — {@code Foo$1} は該当するが、ArchUnit の
 *       {@code getSimpleName()} は合成クラスに対して空文字列を返す実装依存の挙動があり、
 *       名前ベースの判定は ArchUnit のバージョンや javac の生成規則に脆弱に結合する。
 *       {@code SYNTHETIC} 修飾子はクラスファイルのアクセスフラグそのものであり、
 *       より直接的で安定した判定手段である。</li>
 * </ul>
 */
final class SyntheticClasses {

    private SyntheticClasses() {
        // ユーティリティクラス
    }

    /**
     * 対象クラスが javac の生成する合成クラス（{@code ACC_SYNTHETIC} フラグ付き）か
     * どうかを判定する。
     *
     * @param clazz 判定対象のクラス
     * @return 合成クラスであれば {@code true}
     */
    static boolean isSynthetic(JavaClass clazz) {
        return clazz.getModifiers().contains(JavaModifier.SYNTHETIC);
    }
}
