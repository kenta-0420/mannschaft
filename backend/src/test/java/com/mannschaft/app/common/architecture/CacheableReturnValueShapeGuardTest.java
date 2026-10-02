package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Cacheable} / {@code @CachePut} の戻り値が
 * <b>Valkey から復元できない形</b>になっていないことを機械的に固定する番人（issue #2544 の二段番人 (a)）。
 *
 * <h2>背景 — なぜ「効いていないキャッシュ」が量産されるのか</h2>
 * <p>
 * {@code RedisConfig} は {@code GenericJackson2JsonRedisSerializer} ＋
 * {@code activateDefaultTyping(DefaultTyping.EVERYTHING)} を使う。EVERYTHING は
 * コレクションを含む全オブジェクトに<b>具象クラス名</b>を型 ID として JSON へ埋め込むため、
 * 復元時にその具象クラスを Jackson が構築できなければならない。ところが
 * </p>
 * <ul>
 *   <li>{@code Stream#toList()} の実体は {@code java.util.ImmutableCollections$ListN}
 *       （{@code ReferencePipeline} が override。<b>{@code javap java.util.stream.Stream} の
 *       default 実装を読んで {@code ArrayList} だと誤読しないこと</b>）</li>
 *   <li>{@code List.of} / {@code Set.of} / {@code Map.of} / {@code Map.copyOf} も
 *       {@code ImmutableCollections$List12/ListN/MapN}</li>
 *   <li>{@code Collections.unmodifiableXxx} も専用の内部クラス</li>
 * </ul>
 * <p>
 * いずれも既定コンストラクタを持たず<b>復元できない</b>。しかも復元失敗は
 * {@code LoggingCacheErrorHandler} が fail-open で WARN に握り潰すため、
 * 例外にもならず「毎回ミスするだけの効かないキャッシュ」に静かに戻る。
 * さらに test プロファイルは {@code ConcurrentMapCacheManager}（シリアライズを通らない）なので、
 * <b>通常の統合テストでは原理的に検出できない</b>。
 * </p>
 * <p>
 * 同じく {@code org.springframework.data.domain.Page}（{@code PageImpl}）は
 * 可視コンストラクタが複数あり {@code @JsonCreator} も既定コンストラクタも無く、
 * {@code pageable} プロパティの静的型がインタフェースなので復元できない。
 * <b>{@code Page} はキャッシュ値にしない</b>のが筋であり、リストや ID 列をキャッシュして
 * {@code Page} は呼び出し側で組み直す（PR #2513 / issue #2544 がこの形に是正した）。
 * </p>
 *
 * <h2>本番人が固定する不変条件</h2>
 * <ol>
 *   <li>{@code @Cacheable} / {@code @CachePut} の戻り値型（型引数を再帰的に含む）に
 *       {@code Page} / {@code Slice} / {@code Pageable} を含んではならない</li>
 *   <li>当該メソッド、および<b>同一クラス内で到達可能なメソッド</b>（private ヘルパー・
 *       ラムダの synthetic メソッドを含む）から、復元不能な不変コレクション生成
 *       （{@code Stream#toList} / {@code List.of} / {@code Set.of} / {@code Map.of} /
 *       {@code Map.copyOf} / {@code Collections.unmodifiable*} /
 *       {@code Collectors.toUnmodifiable*}）を呼んではならない</li>
 * </ol>
 *
 * <h2>既知の限定（precision 優先）</h2>
 * <ul>
 *   <li><b>他クラス経由の推移呼び出しは追わない</b> — 兄弟番人
 *       {@link CacheableAuthzEnforcementGuardTest} と同じ方針。他 Bean の内部実装まで辿ると
 *       「キャッシュ対象外の場所で {@code List.of()} を使っただけ」を大量に巻き込む。
 *       DTO の内側に不変コレクションが入り込む型（{@code ApiResponse<T>} の {@code T} など）は
 *       姉妹番人 {@code config.CacheValueSerializationRoundTripTest} が
 *       <b>実シリアライザでの往復</b>で担保する。</li>
 *   <li><b>戻り値へ流れない呼び出しも一律に禁止する（過剰近似）</b> —
 *       {@code repository.findByStatusIn(List.of(A, B))} のようにクエリ引数として使う
 *       {@code List.of} も検出する。ArchUnit ではデータフロー（その値が戻り値に流れるか）を
 *       追えず、追えないものを「たぶん安全」と見逃すと本番人の存在意義が消えるためである。
 *       回避策は定数（{@code private static final} ＝ {@code <clinit>} で初期化）へ退避すること。
 *       定数はキャッシュ値に載らず、{@code @Cacheable} メソッドからの到達経路にも入らない。</li>
 *   <li><b>{@code Collectors.toList()} は許可</b> — 現行 JDK では可変の {@code ArrayList} を返す
 *       （issue #2544 の棚卸しでも「安全」に分類）。仕様上は不変性を保証しないので
 *       新規実装では {@code Collectors.toCollection(ArrayList::new)} を推奨する。</li>
 * </ul>
 *
 * <p>凍結ストア（{@code FreezingArchRule}）は使わない。{@code --tests} 絞り込み実行で
 * 凍結ストアを破壊する事故を持ち込まないためであり、
 * <b>引っかかった箇所は凍結せず是正する</b>（凍結は免罪符になる）。</p>
 */
@DisplayName("@Cacheable 戻り値の復元可能性 静的番人 (issue #2544)")
class CacheableReturnValueShapeGuardTest {

    /** キャッシュ値にしてはならない型（Jackson で復元できない）。 */
    private static final Set<String> FORBIDDEN_VALUE_TYPES = Set.of(
            "org.springframework.data.domain.Page",
            "org.springframework.data.domain.PageImpl",
            "org.springframework.data.domain.Slice",
            "org.springframework.data.domain.SliceImpl",
            "org.springframework.data.domain.Pageable"
    );

    /** 復元不能な不変コレクションを生成する呼び出し（オーナー完全名 → メソッド名）。 */
    private static boolean isImmutableFactoryCall(JavaMethodCall call) {
        String owner = call.getTargetOwner().getFullName();
        String name = call.getTarget().getName();

        if ("java.util.stream.Stream".equals(owner) && "toList".equals(name)) {
            return true;
        }
        if (("java.util.List".equals(owner) || "java.util.Set".equals(owner) || "java.util.Map".equals(owner))
                && ("of".equals(name) || "copyOf".equals(name) || "ofEntries".equals(name))) {
            return true;
        }
        if ("java.util.Collections".equals(owner) && name.startsWith("unmodifiable")) {
            return true;
        }
        return "java.util.stream.Collectors".equals(owner) && name.startsWith("toUnmodifiable");
    }

    private static JavaClasses importedClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static boolean isCacheWriting(JavaMethod method) {
        return method.isAnnotatedWith(Cacheable.class) || method.isAnnotatedWith(CachePut.class);
    }

    // ============================================================
    // 不変条件 1: Page / Slice / Pageable を戻り値に含めない
    // ============================================================

    @Test
    @DisplayName("@Cacheable / @CachePut の戻り値型に Page / Slice / Pageable を含めない")
    void キャッシュ値にPageを載せない() {
        List<String> violations = new ArrayList<>();

        for (JavaClass clazz : importedClasses()) {
            for (JavaMethod method : clazz.getMethods()) {
                if (!isCacheWriting(method)) {
                    continue;
                }
                Set<String> found = new HashSet<>();
                collectForbiddenTypes(method.getReturnType(), found);
                if (!found.isEmpty()) {
                    violations.add(method.getFullName() + " の戻り値に " + found
                            + " が含まれる。PageImpl は @JsonCreator も既定コンストラクタも持たず"
                            + "（pageable プロパティの静的型もインタフェース）Valkey から復元できない。"
                            + "ID 列やリストをキャッシュし、Page は呼び出し側で組み直すこと"
                            + "（例: TeamSearchService#searchTeamIdPage）。");
                }
            }
        }

        assertThat(violations)
                .as("復元不能な Page/Slice/Pageable をキャッシュ値にしている箇所")
                .isEmpty();
    }

    /** 戻り値型を型引数まで再帰的に辿り、禁止型を収集する。 */
    private static void collectForbiddenTypes(JavaType type, Set<String> found) {
        String name = type.toErasure().getFullName();
        if (FORBIDDEN_VALUE_TYPES.contains(name)) {
            found.add(name);
        }
        if (type instanceof JavaParameterizedType parameterized) {
            for (JavaType arg : parameterized.getActualTypeArguments()) {
                collectForbiddenTypes(arg, found);
            }
        }
    }

    // ============================================================
    // 不変条件 2: 復元不能な不変コレクションを返さない
    // ============================================================

    @Test
    @DisplayName("@Cacheable / @CachePut から到達する同一クラス内コードで不変コレクションを生成しない")
    void キャッシュ値に不変コレクションを載せない() {
        List<String> violations = new ArrayList<>();

        for (JavaClass clazz : importedClasses()) {
            for (JavaMethod method : clazz.getMethods()) {
                if (!isCacheWriting(method)) {
                    continue;
                }
                for (JavaMethodCall call : findImmutableFactoryCalls(method)) {
                    violations.add(method.getFullName() + " から "
                            + call.getTargetOwner().getSimpleName() + "#" + call.getTarget().getName()
                            + " に到達する（呼び出し元: " + call.getOriginOwner().getSimpleName()
                            + "#" + call.getOrigin().getName() + "）。"
                            + "生成されるのは java.util.ImmutableCollections$* 等の復元不能な実装であり、"
                            + "キャッシュヒット時の復元が fail-open で握り潰されて"
                            + "「効かないキャッシュ」になる。"
                            + "Collectors.toCollection(ArrayList::new) / new ArrayList<>() /"
                            + " new LinkedHashMap<>() など可変の具象実装を返すこと。");
                }
            }
        }

        assertThat(violations)
                .as("復元不能な不変コレクションをキャッシュ値にしている箇所")
                .isEmpty();
    }

    /**
     * {@code @Cacheable} メソッドから、同一クラス内で到達可能な範囲を BFS で辿り、
     * 復元不能な不変コレクション生成呼び出しを収集する。
     *
     * <p>ラムダ式の本体はバイトコード上 {@code lambda$<メソッド名>$<n>} という synthetic メソッドへ
     * 切り出されるため、同一クラス内の {@code lambda$} メソッドも探索対象に含める
     * （{@code stream().map(...).toList()} の {@code map} 内で {@code List.of()} を使う形を逃さない）。</p>
     */
    private static List<JavaMethodCall> findImmutableFactoryCalls(JavaMethod entry) {
        List<JavaMethodCall> hits = new ArrayList<>();
        JavaClass owner = entry.getOwner();

        Set<String> visited = new HashSet<>();
        Deque<JavaMethod> queue = new ArrayDeque<>();
        queue.add(entry);
        visited.add(entry.getFullName());

        // 当該メソッド由来のラムダ synthetic メソッドを起点に加える
        for (JavaMethod candidate : owner.getMethods()) {
            if (candidate.getName().startsWith("lambda$" + entry.getName() + "$")
                    && visited.add(candidate.getFullName())) {
                queue.add(candidate);
            }
        }

        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                if (isImmutableFactoryCall(call)) {
                    hits.add(call);
                    continue;
                }
                // 同一クラス内のメソッドのみ辿る（他 Bean の内部実装は追わない）
                if (!call.getTargetOwner().equals(owner)) {
                    continue;
                }
                call.getTarget().resolveMember().ifPresent(target -> {
                    if (target instanceof JavaMethod nextMethod && visited.add(nextMethod.getFullName())) {
                        queue.add(nextMethod);
                        // 辿った先のラムダも拾う
                        for (JavaMethod lambda : owner.getMethods()) {
                            if (lambda.getName().startsWith("lambda$" + nextMethod.getName() + "$")
                                    && visited.add(lambda.getFullName())) {
                                queue.add(lambda);
                            }
                        }
                    }
                });
            }
        }
        return hits;
    }

    // ============================================================
    // 偽陰性ゼロの確認 — 判定ロジックが実際に何かを見ていること
    // ============================================================

    @Test
    @DisplayName("番人が実際に @Cacheable メソッドを走査していること（0 件走査＝常時緑の防止）")
    void 走査対象が存在すること() {
        long cacheWritingMethods = importedClasses().stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(CacheableReturnValueShapeGuardTest::isCacheWriting)
                .count();

        // 発足時点の実測は 33 箇所（issue #2544 の棚卸し）。
        // 大幅に減った場合は import 設定の壊れ（＝番人が何も見ていない）を疑うこと。
        assertThat(cacheWritingMethods)
                .as("@Cacheable / @CachePut を持つメソッド数")
                .isGreaterThanOrEqualTo(20);
    }
}
