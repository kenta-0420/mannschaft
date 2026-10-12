package com.mannschaft.app.common.architecture;

/**
 * ArchUnit を使うテストクラスに付ける JUnit タグ（CMP-261002-1606）。
 *
 * <p>ArchUnit の本番取り込み（{@link ProductionClasses} と {@code @AnalyzeClasses} の ClassCache）は
 * 1 JVM あたり約 1GB を占める。Spring のテストコンテキストを積む IT と同じワーカー JVM で走らせると
 * {@code -Xmx4g} を超えて OOM になったため、ArchUnit を使うテストは Spring 系とは別の JVM で走らせる。
 * <ul>
 *   <li>JUnit Jupiter のテストクラス: {@code @Tag(ArchUnitTestTag.ARCHUNIT)}</li>
 *   <li>{@code @AnalyzeClasses}（ArchUnit エンジン）のテストクラス: {@code @ArchTag(ArchUnitTestTag.ARCHUNIT)}
 *       （ArchUnit エンジンは Jupiter の {@code @Tag} を読まないため）</li>
 * </ul>
 * 通常の {@code test} タスクはこのタグを除外し、専用タスク {@code archTest} だけがこのタグを走らせる
 * （{@code backend/build.gradle.kts}）。付け忘れ・Spring との混在は {@link ArchUnitTestTagGuardTest} が検出する。
 */
public final class ArchUnitTestTag {

    /** ArchUnit を使うテストクラスのタグ名。 */
    public static final String ARCHUNIT = "archunit";

    private ArchUnitTestTag() {
    }
}
