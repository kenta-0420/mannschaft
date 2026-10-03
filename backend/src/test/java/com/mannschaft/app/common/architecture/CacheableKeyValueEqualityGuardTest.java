package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Cacheable} / {@code @CachePut} の<b>キャッシュキー</b>に、
 * 値等価が保証されない引数が混ざらないことを機械的に固定する番人（issue #2544 検分指摘 must-fix 2）。
 *
 * <h2>背景 — 実際に踏んだ欠陥</h2>
 * <p>
 * {@code TeamSearchService#searchTeamIdPage} は当初、合成済みの
 * {@code org.springframework.data.jpa.domain.Specification} を引数に取り、
 * キー式 {@code T(java.util.Objects).hash(#spec, ...)} でそれをキーへ混ぜていた。
 * ところが {@code Specification.where(...).and(...)} が返すのは
 * {@code SpecificationComposition} が生成する<b>ラムダ</b>であり、
 * {@code hashCode} を override していない。よってキーには <b>identity hash</b> が入る。
 * </p>
 * <ol>
 *   <li>リクエストごとにキーが変わるので<b>キャッシュは 100% ミスする</b>。
 *       {@code unless} が 0 件しか除外しないなら put だけが毎回成功し、
 *       TTL 満了まで Valkey にゴミが積み続ける（＝キャッシュを入れる前と実害が同じ）</li>
 *   <li>identity hash が衝突した場合、テナント ID や可視性スコープがキーに現れないため
 *       <b>別テナント・別条件の結果を掴む理論的経路</b>が残る</li>
 *   <li>identity hash は JVM 再起動で変わるので、キャッシュ上のエントリは永久に孤児になる</li>
 * </ol>
 *
 * <h2>本番人が固定する不変条件</h2>
 * <p>
 * <b>{@code @Cacheable} / {@code @CachePut} を持つメソッドは、
 * 関数型インタフェース（SAM: single abstract method）型の引数を宣言してはならない。</b>
 * </p>
 * <p>
 * 「キー式が参照していなければ良い」ではなく<b>宣言そのものを禁止</b>する。理由は 2 つある。
 * </p>
 * <ul>
 *   <li>{@code key} を省略した場合、Spring の {@code SimpleKeyGenerator} は
 *       <b>全パラメータ</b>からキーを組む。つまり SAM 引数がシグネチャに在るだけで、
 *       キー式を書き忘れた瞬間に同じ欠陥へ落ちる</li>
 *   <li>SpEL 文字列の静的解析でキー参照を追う方式は、
 *       {@code T(...).hash(...)} / 三項演算子 / メソッド呼び出しなどで容易に読み落とす。
 *       宣言禁止なら読み落としが原理的に発生しない</li>
 * </ul>
 * <p>
 * 回避策は「合成をキャッシュ対象メソッドの<b>内側</b>へ移し、
 * 引数は値等価が保証される型（プリミティブ・{@code String}・{@code Long}・record など）だけにする」こと。
 * {@code TeamSearchService#searchTeamIdPage} がその形に是正済みである。
 * </p>
 *
 * <h2>SAM 判定について</h2>
 * <p>
 * インタフェースであり、{@code Object} の public メソッド（{@code equals}/{@code hashCode}/
 * {@code toString}）と同一シグネチャのものを除いた抽象メソッドが<b>ちょうど 1 つ</b>のものを
 * 関数型インタフェースとみなす（{@code @FunctionalInterface} の有無に依存しない。
 * {@code Specification} のように注釈が無くてもラムダで実装される型を逃さないため）。
 * </p>
 * <p>
 * {@code org.springframework.data.domain.Pageable} は抽象メソッドを多数持つため SAM ではなく、
 * 誤検出しない（実体の {@code PageRequest} は {@code equals}/{@code hashCode} を値ベースで実装しており
 * キーに使って安全である）。
 * </p>
 *
 * <p>凍結ストア（{@code FreezingArchRule}）は使わない。{@code --tests} 絞り込み実行で
 * 凍結ストアを破壊する事故を持ち込まないためであり、<b>引っかかった箇所は凍結せず是正する</b>。</p>
 */
@DisplayName("@Cacheable キー引数の値等価 静的番人 (issue #2544)")
class CacheableKeyValueEqualityGuardTest {

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static boolean isCacheWriting(JavaMethod method) {
        return method.isAnnotatedWith(Cacheable.class) || method.isAnnotatedWith(CachePut.class);
    }

    /**
     * 関数型インタフェース（SAM）判定。
     *
     * <p>{@code Object} の public メソッドと同一シグネチャの抽象宣言は数えない
     * （{@code Comparator#equals} のような再宣言を SAM の勘定から外すため）。</p>
     */
    private static boolean isFunctionalInterface(Class<?> type) {
        if (type == null || !type.isInterface()) {
            return false;
        }
        long abstractMethods = 0;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || method.isDefault() || method.isSynthetic()) {
                continue;
            }
            if (isObjectPublicMethod(method)) {
                continue;
            }
            abstractMethods++;
        }
        return abstractMethods == 1;
    }

    private static boolean isObjectPublicMethod(Method method) {
        try {
            Method objectMethod = Object.class.getMethod(method.getName(), method.getParameterTypes());
            return Modifier.isPublic(objectMethod.getModifiers());
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    @Test
    @DisplayName("@Cacheable / @CachePut のメソッドは関数型インタフェース型の引数を取らない（identity hash キーの禁止）")
    void キャッシュ対象メソッドはラムダ型引数を取らない() {
        List<String> violations = new ArrayList<>();

        for (JavaClass clazz : importedClasses()) {
            for (JavaMethod method : clazz.getMethods()) {
                if (!isCacheWriting(method)) {
                    continue;
                }
                for (JavaParameter parameter : method.getParameters()) {
                    Class<?> rawType = resolveRawType(parameter);
                    if (rawType == null || !isFunctionalInterface(rawType)) {
                        continue;
                    }
                    violations.add(method.getFullName() + " が関数型インタフェース型の引数 "
                            + rawType.getName() + " を宣言している。"
                            + "ラムダは hashCode を override しないため identity hash がキーに入り、"
                            + "(1) キャッシュが 100% ミスして put だけが積む "
                            + "(2) 衝突時にテナント境界・可視性境界を越える理論的経路が残る "
                            + "(3) JVM 再起動でエントリが孤児になる。"
                            + "key を明示していても SimpleKeyGenerator へ戻った瞬間に同じ穴が開くため、"
                            + "宣言そのものを禁止する。"
                            + "合成はキャッシュ対象メソッドの内側へ移し、"
                            + "引数は値等価が保証される型（プリミティブ / String / Long / record 等）"
                            + "だけにすること（例: TeamSearchService#searchTeamIdPage）。");
                }
            }
        }

        assertThat(violations)
                .as("値等価が保証されない引数をキャッシュキーに巻き込みうる箇所")
                .isEmpty();
    }

    /** ArchUnit の型情報から実クラスを解決する（解決不能なら null を返して判定をスキップ）。 */
    private static Class<?> resolveRawType(JavaParameter parameter) {
        try {
            return parameter.getRawType().reflect();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ============================================================
    // 偽陰性ゼロの確認 — 判定ロジックが実際に働くこと
    // ============================================================

    @Test
    @DisplayName("SAM 判定が正しく働く（Specification は検出し、Pageable / record は検出しない）")
    void SAM判定のメタテスト() {
        // 実際に踏んだ型: ラムダで実装され hashCode を持たない
        assertThat(isFunctionalInterface(org.springframework.data.jpa.domain.Specification.class))
                .as("Specification を SAM と判定できなければ、この番人は本件を検出できない")
                .isTrue();

        // 誤検出してはならない型
        assertThat(isFunctionalInterface(org.springframework.data.domain.Pageable.class))
                .as("Pageable は抽象メソッドを多数持つ。実体 PageRequest は値等価でキーに使って安全")
                .isFalse();
        assertThat(isFunctionalInterface(com.mannschaft.app.team.dto.TeamSearchCriteria.class))
                .as("record はインタフェースではない")
                .isFalse();
        assertThat(isFunctionalInterface(String.class)).isFalse();
        assertThat(isFunctionalInterface(Long.class)).isFalse();
    }

    @Test
    @DisplayName("番人が実際に @Cacheable メソッドを走査していること（0 件走査＝常時緑の防止）")
    void 走査対象が存在すること() {
        long cacheWritingMethods = importedClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(CacheableKeyValueEqualityGuardTest::isCacheWriting)
                .count();

        assertThat(cacheWritingMethods)
                .as("@Cacheable / @CachePut を持つメソッド数")
                .isGreaterThanOrEqualTo(20);
    }
}
