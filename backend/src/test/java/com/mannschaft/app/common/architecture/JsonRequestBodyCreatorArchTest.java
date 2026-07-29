package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * JSON リクエストボディ DTO の Jackson デシリアライズ可能性の番人テスト（D-7）:
 * <b>{@code @RequestBody}/{@code @RequestPart} でバインドされる型（およびその入れ子 DTO）は、
 * Jackson が実体を生成できる「creator」を必ず持たねばならない</b>。
 *
 * <h2>なぜ番人が要るのか — 「常時 500」という最悪の壊れ方</h2>
 * <p>Jackson はコンストラクタが複数あると、どれをデシリアライズに使うか一意に決められない。
 * そこへ「全フィールド {@code final}（＝ setter 無し）」「引数無しコンストラクタ無し」が重なると、
 * Jackson は <b>「no suitable creator」</b>（{@code InvalidDefinitionException}）で
 * デシリアライザの構築自体に失敗する。</p>
 *
 * <p>Spring の {@code AbstractJackson2HttpMessageConverter} はこれを
 * <b>{@code HttpMessageConversionException}</b> として投げるが、これは 400 にマップ済みの
 * {@code HttpMessageNotReadableException} とは<b>別系統</b>であり、{@code GlobalExceptionHandler}
 * に個別ハンドラが無い。結果、<b>クライアントが何を送っても当該 POST/PUT が 500</b> になる。
 * しかも<b>エラーコードの付かない素の例外</b>なので、{@code ERROR_CODE_STATUS_MAP} や
 * {@code Severity} の整備をいくら進めても直らない。</p>
 *
 * <h2>Mock ベースのテストでは原理的に検出できない</h2>
 * <p>Service 層のユニットテストは DTO を直接 {@code new} するためデシリアライズ経路を通らず、
 * 壊れた DTO を素通りさせる。実 HTTP を叩く契約テストが当該 EP に 1 本も無ければ、
 * 障害は本番で初めて露見する。</p>
 *
 * <h2>再発の履歴（同一改修で片方だけ直るという形で 2 度）</h2>
 * <table>
 *   <caption>過去の被害</caption>
 *   <tr><th>DTO</th><th>経緯</th></tr>
 *   <tr><td>{@code chat.dto.SendMessageRequest}</td>
 *       <td>F17.1 Phase 3 で 2 コンストラクタ化 → 発見・{@code @JsonCreator} 付与で是正済み。
 *           同 DTO の Javadoc に「これがないと 500 で落ちる」と明記されている。</td></tr>
 *   <tr><td>{@code bulletin.dto.CreateThreadRequest}</td>
 *       <td>同じ F17.1 Phase 3 の同型改修でありながら取り残され、
 *           {@code POST /api/v1/&#123;scopeType&#125;/&#123;scopeId&#125;/bulletin/threads} が
 *           <b>常時 500</b> だった（2026-07-29 / PR #2503 で是正）。</td></tr>
 * </table>
 * <p>人間のレビューが 2 度続けて取り逃した事象であり、機械検出でしか止められない。</p>
 *
 * <h2>検査対象の集合（到達可能性で決める。命名では決めない）</h2>
 * <p>{@code *Request} という<b>命名</b>での判定は行わない。本リポには
 * 「{@code *Request} だが JSON デシリアライズされない内部オブジェクト」が存在する一方、
 * 「{@code *Request} で終わらないがボディにバインドされる DTO」も存在するため、命名判定は
 * 偽陽性と偽陰性を同時に生む。代わりに<b>実際の到達可能性</b>で対象を決める:</p>
 * <ol>
 *   <li><b>根</b>: {@code @RestController}/{@code @Controller} の public Mapping メソッドの
 *       引数のうち、{@code @RequestBody} または {@code @RequestPart} が付いたものの型
 *       （{@code List<Foo>} 等のジェネリクス入れ子は全関与生型へ展開する）。</li>
 *   <li><b>閉包</b>: 根から、宣言フィールド（継承フィールド含む・{@code static} 除く）の型を
 *       たどって推移的に到達できる {@code com.mannschaft.app} 配下のクラス。
 *       {@code SendMessageRequest.attachments} の {@code List<AttachmentRequest>} のような
 *       <b>入れ子 DTO も同じ壊れ方をする</b>ため、閉包に含めて検査する。</li>
 * </ol>
 *
 * <h2>違反条件（Jackson が「実体を作れない」ことの十分条件のみを採る）</h2>
 * <p>上記集合に属する<b>具象クラス</b>（interface/abstract/enum/annotation/record/合成クラスを除く）
 * であって、次を<b>すべて</b>満たすものを違反とする:</p>
 * <ul>
 *   <li>宣言コンストラクタが <b>2 本以上</b>ある</li>
 *   <li>{@code @JsonCreator} の付いたコンストラクタが<b>無い</b></li>
 *   <li>{@code @JsonCreator} の付いた<b>static ファクトリメソッド</b>も<b>無い</b></li>
 *   <li><b>引数無しコンストラクタが無い</b></li>
 *   <li>クラスに {@code @JsonDeserialize}（カスタム deserializer / builder 指定）が<b>無い</b></li>
 * </ul>
 *
 * <h3>なぜ「全フィールド final」を条件に含めないのか</h3>
 * <p>「引数無しコンストラクタが無い」時点で、setter の有無に関わらず Jackson は<b>実体を生成できない</b>
 * （setter は生成後の代入手段であって生成手段ではない）。したがって final 判定は不要であり、
 * 条件に足すと「非 final だが同じく 500 になる DTO」を取り逃す。
 * 逆に<b>引数無しコンストラクタがある</b>場合は Jackson が既定 creator としてそれを使えるため、
 * 「creator 不在で 500」には該当しない（Lombok の
 * {@code @NoArgsConstructor + @AllArgsConstructor + @Data} 様式はここで正しく合格になる）。</p>
 *
 * <h3>なぜ「コンストラクタ 1 本」を違反にしないのか</h3>
 * <p>本リポは Spring Boot 既定の {@code ParameterNamesModule} を使い、コンパイラ引数
 * {@code -parameters}（{@code build.gradle.kts} の {@code JavaCompile} 設定）が有効である。
 * この構成では<b>唯一の引数付きコンストラクタ</b>は暗黙の properties-based creator として
 * 採用されるため、{@code @JsonCreator} 無しでも動作する。
 *
 * <p><b>実測による裏取り</b>: {@code SendMessageRequest} は初版（commit {@code 1113d573b}）では
 * {@code @Getter @RequiredArgsConstructor} ＋ 全フィールド {@code final}
 * （＝コンストラクタ 1 本・{@code @JsonCreator} 無し・setter 無し）であり、
 * チャット送信 EP は正常に動作していた。壊れたのは F17 Phase 3（{@code 807c66985}）で
 * <b>2 本目のコンストラクタが増えた瞬間</b>であり、{@code @JsonCreator} 付与（{@code 08a752788} /
 * PR #2033）で回復した。<b>「複数あるから一意に決められない」</b>ことが病因であり、そこだけを狙う。</p>
 *
 * <h2>凍結しない恒久ルール</h2>
 * <p>{@link com.tngtech.archunit.library.freeze.FreezingArchRule} を用いず素の {@link ArchRule}
 * として恒久導入する。既存違反は本 PR ですべて根治しており凍結ストアを持たないため、
 * {@link ArchUnitFreezeStoreIntegrityTest} の管理対象外である
 * （{@code memory/feedback_baseline_suppression_is_debt}: 凍結は免罪符にしない）。</p>
 *
 * <h2>限界（既知の偽陰性）</h2>
 * <ul>
 *   <li>{@code @JsonTypeInfo}/{@code @JsonSubTypes} によるポリモーフィック body の派生型は、
 *       フィールド型経由で到達しないため閉包に入らない。</li>
 *   <li>{@code Map<String, Object>} で受けてから手動で {@code ObjectMapper.convertValue} する
 *       経路は追跡しない。</li>
 *   <li>「引数無しコンストラクタはあるが全フィールド final で setter も無い」型は、Jackson が
 *       実体生成に成功して<b>全フィールド null のまま返す</b>（500 ではなく静かなデータ欠落）。
 *       壊れ方が別物なので本ルールの対象外とする。</li>
 * </ul>
 *
 * <p>合格判定の単一正準は {@link #jsonBodyBoundTypes(Collection)} と
 * {@link #lacksUsableJacksonCreator(JavaClass)}。本番番人と、偽陰性ゼロ・偽陽性ゼロを証明する
 * メタテスト {@link JsonRequestBodyCreatorConditionTest} の双方から呼ばれる。
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class JsonRequestBodyCreatorArchTest {

    /** Jackson の creator マーカー FQN。 */
    static final String JSON_CREATOR_ANNOTATION = "com.fasterxml.jackson.annotation.JsonCreator";

    /** カスタム deserializer / builder 指定の脱出口 FQN。 */
    static final String JSON_DESERIALIZE_ANNOTATION =
        "com.fasterxml.jackson.databind.annotation.JsonDeserialize";

    /** JSON ボディバインドの起点となる引数アノテーション FQN。 */
    static final String REQUEST_BODY_ANNOTATION =
        "org.springframework.web.bind.annotation.RequestBody";

    static final String REQUEST_PART_ANNOTATION =
        "org.springframework.web.bind.annotation.RequestPart";

    /** 閉包をアプリ配下に限定するためのパッケージ接頭辞。 */
    private static final String APP_PACKAGE_PREFIX = "com.mannschaft.app.";

    /** D-7 検査対象の下限（走査ロジック破損の検知用。実測 1085 型に対する保守的な下限）。 */
    private static final int MIN_EXPECTED_JSON_BODY_BOUND_TYPES = 500;

    @ArchTest
    static final ArchRule json_request_body_types_must_have_a_jackson_creator =
        classes().should(haveAJacksonUsableCreatorWhenBoundAsJsonBody())
            .because("複数コンストラクタ＋@JsonCreator 不在＋引数無しコンストラクタ不在の DTO は、"
                + "Jackson がデシリアライザを構築できず（no suitable creator）Spring が "
                + "HttpMessageConversionException を投げる。同例外は GlobalExceptionHandler に "
                + "個別ハンドラが無いため、当該エンドポイントは body の内容によらず常に 500 になる。"
                + "chat.SendMessageRequest / bulletin.CreateThreadRequest で 2 度再発した事故であり、"
                + "Mock ベースの UT では原理的に検出できないため機械検出する。"
                + "是正の金型は chat.dto.SendMessageRequest（完全コンストラクタに @JsonCreator と "
                + "各引数の @JsonProperty を付与）")
            .as("json request body types must have a Jackson-usable creator (D-7)");

    /**
     * 検査対象が空になっていないことの<b>自己健全性チェック</b>（番人の番人）。
     *
     * <p>D-7 の本体ルールは「検査対象集合に入った型」だけを見る。もし
     * {@link #jsonBodyBoundTypes(Collection)} が ArchUnit の API 変更・Controller 構造の変化などで
     * <b>静かに空集合を返すようになった</b>場合、本体ルールは違反 0 件で<b>常に緑</b>になり、
     * 番人が死んだことに誰も気付けない（{@code memory/feedback_verify_tool_output_fabrication} と
     * 同じ「空＝合格」の罠）。それを防ぐため、走査結果が十分な規模であることを固定する。
     *
     * <p>閾値 500 の根拠: 2026-07-29 時点の実測で走査結果は <b>1085 型</b>（main の
     * {@code @RequestBody} 含有ソースは 500 本超）。閾値はその半分未満に置いた保守的な下限であり、
     * 「正常な削減」で割り込むことは想定しない。割り込んだ場合は走査ロジックの破損を疑うこと。
     */
    @ArchTest
    static void json_body_bound_type_scan_must_not_silently_become_empty(JavaClasses classes) {
        int scanned = jsonBodyBoundTypes(classes).size();
        if (scanned < MIN_EXPECTED_JSON_BODY_BOUND_TYPES) {
            throw new AssertionError(String.format(
                "D-7 の検査対象（@RequestBody/@RequestPart から到達する型）が %d 件しか見つからなかった"
                    + "（期待: %d 件以上）。走査ロジックが壊れて番人が「常に緑」になっている疑いが強い。"
                    + "jsonBodyBoundTypes() の Controller 走査・フィールド閉包を確認すること。",
                scanned, MIN_EXPECTED_JSON_BODY_BOUND_TYPES));
        }
    }

    // ------------------------------------------------------------------
    // 判定の単一正準（本番番人とメタテストが共有する）
    // ------------------------------------------------------------------

    /**
     * 全解析対象クラスから「JSON ボディとしてデシリアライズされ得る型」の集合を返す。
     *
     * <p>根は Controller の public Mapping メソッドの {@code @RequestBody}/{@code @RequestPart}
     * 引数型（ジェネリクス入れ子を展開）。そこからフィールド型を推移的にたどって
     * {@code com.mannschaft.app} 配下のクラスへ閉包を取る。
     */
    static Set<JavaClass> jsonBodyBoundTypes(Collection<JavaClass> allClasses) {
        Set<JavaClass> reachable = new LinkedHashSet<>();
        Deque<JavaClass> queue = new ArrayDeque<>();
        DescribedPredicate<JavaMethod> isMappingEndpoint =
            ControllerEndpoints.areMappingEndpointsOfControllerClasses();

        for (JavaClass clazz : allClasses) {
            if (!ControllerEndpoints.isControllerClass(clazz)) {
                continue;
            }
            for (JavaMethod method : clazz.getMethods()) {
                if (!isMappingEndpoint.test(method)) {
                    continue;
                }
                for (JavaParameter parameter : method.getParameters()) {
                    if (!isJsonBodyParameter(parameter)) {
                        continue;
                    }
                    for (JavaClass rawType : parameter.getType().getAllInvolvedRawTypes()) {
                        enqueueIfAppClass(rawType, reachable, queue);
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            JavaClass current = queue.removeFirst();
            for (JavaField field : current.getAllFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                for (JavaClass rawType : field.getType().getAllInvolvedRawTypes()) {
                    enqueueIfAppClass(rawType, reachable, queue);
                }
            }
        }
        return reachable;
    }

    /**
     * クラスが「Jackson から実体生成不能」＝ D-7 違反かどうかを判定する。
     *
     * <p>判定は<b>十分条件のみ</b>を採る（偽陽性を出すと番人が信用されなくなるため）。
     * 詳細な根拠はクラス Javadoc の「違反条件」節を参照。
     */
    static boolean lacksUsableJacksonCreator(JavaClass clazz) {
        if (!isConcreteDeserializableClass(clazz)) {
            return false;
        }
        if (clazz.isAnnotatedWith(JSON_DESERIALIZE_ANNOTATION)) {
            // カスタム deserializer / builder が明示されている場合はコンストラクタ事情に依存しない。
            return false;
        }
        Set<JavaConstructor> constructors = clazz.getConstructors();
        if (constructors.size() < 2) {
            // 単一コンストラクタは -parameters + ParameterNamesModule で暗黙 creator になる。
            return false;
        }
        if (hasNoArgConstructor(constructors)) {
            // 引数無しコンストラクタがあれば Jackson は既定 creator として実体を生成できる。
            return false;
        }
        if (hasJsonCreatorConstructor(constructors)) {
            return false;
        }
        return !hasJsonCreatorStaticFactory(clazz);
    }

    /** 違反クラス 1 件分の説明文（本番番人とメタテストで文言を共有する）。 */
    static String violationMessage(JavaClass clazz) {
        Set<String> signatures = new TreeSet<>(clazz.getConstructors().stream()
            .map(JsonRequestBodyCreatorArchTest::constructorSignature)
            .toList());
        return String.format(
            "%s is bound as a JSON request body but Jackson cannot construct it: "
                + "it declares %d constructors %s, none annotated with @JsonCreator, "
                + "and it has no no-arg constructor. "
                + "Every request to the endpoint(s) taking this type will fail with HTTP 500 "
                + "(no suitable creator). "
                + "Fix: annotate the full constructor with @JsonCreator and each parameter with "
                + "@JsonProperty(\"...\"), following chat.dto.SendMessageRequest",
            clazz.getName(), clazz.getConstructors().size(), signatures);
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaClass> haveAJacksonUsableCreatorWhenBoundAsJsonBody() {
        return new ArchCondition<>(
                "have a Jackson-usable creator when bound as a JSON request body "
                    + "(@JsonCreator constructor/factory, or a no-arg constructor, "
                    + "or a single constructor)") {

            private Set<String> boundTypeNames = Set.of();

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                boundTypeNames = new LinkedHashSet<>();
                for (JavaClass boundType : jsonBodyBoundTypes(allObjectsToTest)) {
                    boundTypeNames.add(boundType.getName());
                }
            }

            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                if (!boundTypeNames.contains(clazz.getName())) {
                    // JSON ボディにバインドされない型は本ルールの対象外（イベントを立てない）。
                    return;
                }
                if (lacksUsableJacksonCreator(clazz)) {
                    events.add(SimpleConditionEvent.violated(clazz, violationMessage(clazz)));
                } else {
                    events.add(SimpleConditionEvent.satisfied(clazz,
                        clazz.getName() + " has a Jackson-usable creator"));
                }
            }
        };
    }

    /** 引数に {@code @RequestBody} または {@code @RequestPart} が付いているか。 */
    private static boolean isJsonBodyParameter(JavaParameter parameter) {
        return parameter.isAnnotatedWith(REQUEST_BODY_ANNOTATION)
            || parameter.isAnnotatedWith(REQUEST_PART_ANNOTATION);
    }

    /** アプリ配下の未訪問クラスなら閉包へ追加する。 */
    private static void enqueueIfAppClass(
            JavaClass clazz, Set<JavaClass> reachable, Deque<JavaClass> queue) {
        if (!clazz.getName().startsWith(APP_PACKAGE_PREFIX)) {
            return;
        }
        if (clazz.isArray() || clazz.isEnum() || clazz.isInterface()) {
            return;
        }
        if (reachable.add(clazz)) {
            queue.addLast(clazz);
        }
    }

    /** デシリアライズ先になり得る具象クラスか（interface/abstract/enum/record/合成を除く）。 */
    private static boolean isConcreteDeserializableClass(JavaClass clazz) {
        if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotation() || clazz.isArray()) {
            return false;
        }
        if (clazz.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        if (isRecord(clazz)) {
            // record は正準コンストラクタが Jackson 2.12+ でネイティブ解決されるため対象外。
            return false;
        }
        // 匿名クラス・ローカルクラス・合成クラスは JSON バインド先にならない。
        return !clazz.getSimpleName().isEmpty() && !clazz.getName().contains("$$");
    }

    /** {@code java.lang.Record} を直接の親に持つか（ArchUnit 1.3 に record 述語が無いため名前判定）。 */
    private static boolean isRecord(JavaClass clazz) {
        return clazz.getRawSuperclass()
            .map(superclass -> "java.lang.Record".equals(superclass.getName()))
            .orElse(false);
    }

    private static boolean hasNoArgConstructor(Set<JavaConstructor> constructors) {
        return constructors.stream()
            .anyMatch(constructor -> constructor.getRawParameterTypes().isEmpty());
    }

    private static boolean hasJsonCreatorConstructor(Set<JavaConstructor> constructors) {
        return constructors.stream()
            .anyMatch(constructor -> constructor.isAnnotatedWith(JSON_CREATOR_ANNOTATION));
    }

    private static boolean hasJsonCreatorStaticFactory(JavaClass clazz) {
        return clazz.getMethods().stream()
            .filter(method -> method.getModifiers().contains(JavaModifier.STATIC))
            .anyMatch(method -> method.isAnnotatedWith(JSON_CREATOR_ANNOTATION));
    }

    /** 違反メッセージ用のコンストラクタ引数型シグネチャ（宣言順を保つ）。 */
    private static String constructorSignature(JavaConstructor constructor) {
        return constructor.getRawParameterTypes().stream()
            .map(JavaClass::getSimpleName)
            .toList()
            .toString();
    }
}
