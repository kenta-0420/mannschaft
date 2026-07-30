package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * リクエストペイロード DTO の「バインダから実体生成できること」の番人テスト（D-7）:
 * <b>{@code @RequestBody} / {@code @RequestPart} / {@code @ModelAttribute} でバインドされる型
 * （およびその入れ子 DTO）は、担当バインダが実体を生成できる手段を必ず持たねばならない</b>。
 *
 * <h2>なぜ番人が要るのか — 「常時 500」という最悪の壊れ方</h2>
 * <p>Jackson はコンストラクタが複数あると、どれをデシリアライズに使うか一意に決められない。
 * そこへ「引数無しコンストラクタ無し」が重なると、Jackson は
 * <b>「no suitable creator」</b>（{@code InvalidDefinitionException}）でデシリアライザの構築自体に
 * 失敗する。</p>
 *
 * <p>Spring の {@code AbstractJackson2HttpMessageConverter} はこれを
 * <b>{@code HttpMessageConversionException}</b> として投げるが、これは 400 にマップ済みの
 * {@code HttpMessageNotReadableException} とは<b>別系統</b>であり、{@code GlobalExceptionHandler}
 * に個別ハンドラが無い。結果、汎用ハンドラに落ちて <b>クライアントが何を送っても
 * 500（{@code COMMON_999}）</b> になる。しかも<b>エラーコードの付かない素の例外</b>なので、
 * {@code ERROR_CODE_STATUS_MAP} や {@code Severity} の整備をいくら進めても直らない。</p>
 *
 * <p>同じ壊れ方が<b>フォームバインド側にもある</b>。{@code @ModelAttribute}（および Controller
 * メソッドの無注釈複合型引数）は {@code ModelAttributeMethodProcessor} が
 * {@code BeanUtils.getResolvableConstructor} で実体を作るが、これは
 * 「Kotlin primary → <b>宣言コンストラクタがちょうど 1 本</b> → 引数無しコンストラクタ」の順にしか
 * 解決しない。したがって<b>コンストラクタ 2 本以上かつ引数無しコンストラクタ無し</b>だと
 * {@code IllegalStateException} を投げて同じく 500 になる。壊れ方が同じなので同じ番人で守る。</p>
 *
 * <h2>Mock ベースのテストでは原理的に検出できない</h2>
 * <p>Service 層のユニットテストは DTO を直接 {@code new} するためバインド経路を通らず、
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
 * <h2>検査対象の集合（到達可能性で決める。命名でも形状でも決めない）</h2>
 * <p>{@code *Request} という<b>命名</b>での判定は行わない。実測（2026-07-29）では
 * {@code *Request} 命名の 932 クラスのうち <b>19 件はどのボディにもバインドされない</b>
 * （{@code CspReportRequest} / {@code RecruitmentListingSearchRequest} /
 * {@code WsSendMessageRequest} / {@code SesNotificationRequest} 等）＝命名で絞ると誤検出側に入り、
 * 逆に閉包に含まれる <b>112 型は {@code *Request} で終わらない</b>
 * （{@code AddContactRequestBlockBody} / {@code BroadcastRequestDto} /
 * {@code BusinessHourEntry} / 各種 enum 等）＝命名で絞ると取り逃す。
 * また<b>形状だけ</b>（コンストラクタ本数など）で判定すると、バインドされない 13〜15 件
 * （{@code BusinessException} / {@code TokenResponse} / {@code LoginSuccessEvent} 等の
 * 例外・イベント・サービス）が<b>全件偽陽性</b>になる。よって<b>実際の到達可能性</b>で対象を決める:</p>
 * <ol>
 *   <li><b>根</b>: {@code @RestController}/{@code @Controller} の public Mapping メソッドの引数のうち
 *     <ul>
 *       <li><b>JSON 経路</b>: {@code @RequestBody} / {@code @RequestPart} が付いた引数の型</li>
 *       <li><b>フォーム経路</b>: {@code @ModelAttribute} が付いた引数、および
 *           <b>アノテーションを一切持たない複合型引数</b>（Spring が暗黙 {@code @ModelAttribute}
 *           として扱う）の型</li>
 *     </ul>
 *     いずれも {@code List<Foo>} 等のジェネリクス入れ子は全関与生型へ展開し、
 *     配列 {@code Foo[]} は要素型まで剥がす。</li>
 *   <li><b>閉包</b>: 根から、宣言フィールド（継承フィールド含む・{@code static} 除く）の型を
 *       たどって推移的に到達できる {@code com.mannschaft.app} 配下のクラス。
 *       {@code SendMessageRequest.attachments} の {@code List<AttachmentRequest>} のような
 *       <b>入れ子 DTO も同じ壊れ方をする</b>ため、閉包に含めて検査する。</li>
 * </ol>
 *
 * <h2>違反条件（バインダが「実体を作れない」ことの十分条件のみを採る）</h2>
 *
 * <h3>JSON 経路（{@code @RequestBody} / {@code @RequestPart} の閉包）</h3>
 * <p><b>具象クラス</b>（interface/abstract/enum/annotation/record/合成クラスを除く）であって、
 * 次を<b>すべて</b>満たすものを違反とする:</p>
 * <ul>
 *   <li>宣言コンストラクタが <b>2 本以上</b>ある</li>
 *   <li>{@code @JsonCreator} の付いたコンストラクタが<b>無い</b></li>
 *   <li>{@code @JsonCreator} の付いた<b>static ファクトリメソッド</b>も<b>無い</b></li>
 *   <li><b>引数無しコンストラクタが無い</b></li>
 *   <li>{@code @JsonDeserialize} で<b>クラス自身の生成手段</b>（{@code using} または {@code builder}）
 *       が指定されていない</li>
 * </ul>
 * <p>{@code @JsonDeserialize} の免責を {@code using} / {@code builder} に限定しているのは、
 * {@code as} / {@code contentAs} / {@code keyAs} / {@code contentUsing} / {@code keyUsing} は
 * <b>そのクラス自身の生成手段を一切与えない</b>ため（要素型や別実装型の指定にすぎない）。
 * 注釈が付いているだけで免責すると、壊れた DTO を素通りさせる抜け道になる。</p>
 *
 * <h3>フォーム経路（{@code @ModelAttribute} / 無注釈複合型の閉包）</h3>
 * <p><b>具象クラス</b>（interface/abstract/enum/annotation/合成クラスを除く。<b>record は除外しない</b>）
 * であって、次を<b>すべて</b>満たすものを違反とする:</p>
 * <ul>
 *   <li>宣言コンストラクタが <b>2 本以上</b>ある</li>
 *   <li><b>引数無しコンストラクタが無い</b></li>
 * </ul>
 * <p>フォーム経路では {@code @JsonCreator} も {@code @JsonDeserialize} も<b>効かない</b>
 * （{@code BeanUtils.getResolvableConstructor} は Jackson の注釈を一切見ない）。
 * record も {@code getDeclaredConstructors().length == 1} でなければ解決に失敗するため、
 * JSON 経路と違って除外しない。</p>
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
 * <p>本リポは Spring Boot 既定の {@code ParameterNamesModule} を {@code JacksonConfig} で明示登録し、
 * コンパイラ引数 {@code -parameters}（{@code build.gradle.kts} の {@code JavaCompile} 設定）を
 * 有効にしている。この構成では<b>唯一の引数付きコンストラクタ</b>は暗黙の properties-based creator
 * として採用されるため、{@code @JsonCreator} 無しでも動作する。
 * フォーム経路の {@code BeanUtils.getResolvableConstructor} も「宣言コンストラクタが 1 本」を
 * そのまま採用する。
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
 * として恒久導入する。既存違反は 0 件であり凍結ストアを持たないため、
 * {@link ArchUnitFreezeStoreIntegrityTest} の管理対象外である
 * （{@code memory/feedback_baseline_suppression_is_debt}: 凍結は免罪符にしない）。</p>
 *
 * <h2>限界（既知の偽陰性）</h2>
 * <ul>
 *   <li>{@code @JsonTypeInfo}/{@code @JsonSubTypes} によるポリモーフィック body の派生型は、
 *       フィールド型経由で到達しないため閉包に入らない。</li>
 *   <li><b>{@code @RequestBody}/{@code @RequestPart} を {@code String} で受けて
 *       {@code objectMapper.readValue} する手動デシリアライズ経路は追跡しない。</b>
 *       実測（2026-07-29）で該当は 6 EP。うち
 *       {@code bulletin.GlobalBulletinThreadController.createThreadMultipart}
 *       （{@code @RequestPart("data") String} → {@code GlobalCreateThreadRequest}）は、
 *       同 Controller の JSON 版 {@code @RequestBody} からも同型に到達するため<b>偶然カバーされている</b>。
 *       {@code cspreport.CspReportController}（{@code @RequestBody String} →
 *       {@code CspReportWrapper}/{@code CspReportRequest}）は<b>閉包に入っていない</b>が、
 *       同 EP はパース例外を捕捉して 204 を返す実装なので 500 にはならない。
 *       残りは Stripe / LINE の Webhook で、いずれも生文字列の署名検証が目的。</li>
 *   <li>アノテーションを持たない複合型引数のうち、<b>カスタム
 *       {@code HandlerMethodArgumentResolver}</b> が解決するアプリ型があれば偽陽性になり得る。
 *       実測では無注釈のアプリパッケージ引数は <b>0 件</b>（無注釈引数の型は
 *       {@code HttpServletRequest} / {@code HttpServletResponse} / {@code Pageable} /
 *       {@code Authentication} の 4 つだけで、いずれもアプリ外なので閉包に入らない）。</li>
 *   <li><b>「バインダは実体を作れるが値が入らない」沈黙型は対象外。</b>
 *       Jackson は既定で {@code INFER_PROPERTY_MUTATORS} と
 *       {@code ALLOW_FINAL_FIELDS_AS_MUTATORS} が有効（{@code JacksonConfig} も無効化していない）ため、
 *       <b>可視 getter が対になっている {@code private final} フィールドは反射で書き込まれる</b>。
 *       よって沈黙型になるのは「可視 getter も setter も public フィールドも {@code @JsonProperty} も
 *       無い」場合に限られる。実測（2026-07-29）で閉包内の該当は <b>0 件</b>。
 *       将来番人化する場合はこの条件で作ること（「全 final ＋ setter 無し」で作ると偽陽性を量産する）。</li>
 * </ul>
 *
 * <p>合格判定の単一正準は {@link #requestPayloadBoundTypes(Collection)} /
 * {@link #lacksUsableJacksonCreator(JavaClass)} / {@link #lacksResolvableConstructor(JavaClass)}。
 * 本番番人と、偽陰性ゼロ・偽陽性ゼロを証明するメタテスト
 * {@link JsonRequestBodyCreatorConditionTest} の双方から呼ばれる。
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

    /** {@code @JsonDeserialize#using} の既定値（＝未指定）。 */
    private static final String JSON_DESERIALIZER_NONE =
        "com.fasterxml.jackson.databind.JsonDeserializer$None";

    /** {@code @JsonDeserialize#builder} の既定値（＝未指定）。 */
    private static final String VOID_CLASS = "java.lang.Void";

    /** JSON ボディバインドの起点となる引数アノテーション FQN。 */
    static final String REQUEST_BODY_ANNOTATION =
        "org.springframework.web.bind.annotation.RequestBody";

    static final String REQUEST_PART_ANNOTATION =
        "org.springframework.web.bind.annotation.RequestPart";

    /** フォームバインドの起点となる引数アノテーション FQN。 */
    static final String MODEL_ATTRIBUTE_ANNOTATION =
        "org.springframework.web.bind.annotation.ModelAttribute";

    /** 閉包をアプリ配下に限定するためのパッケージ接頭辞。 */
    private static final String APP_PACKAGE_PREFIX = "com.mannschaft.app.";

    /** D-7 検査対象の下限（走査ロジック破損の検知用。実測 1085 型に対する保守的な下限）。 */
    private static final int MIN_EXPECTED_PAYLOAD_BOUND_TYPES = 500;

    @ArchTest
    static final ArchRule request_payload_types_must_be_constructible_by_their_binder =
        classes().should(beConstructibleByTheirBinder())
            .because("複数コンストラクタ＋引数無しコンストラクタ不在の DTO は、JSON 経路では Jackson が "
                + "デシリアライザを構築できず（no suitable creator）Spring が "
                + "HttpMessageConversionException を、フォーム経路では "
                + "BeanUtils.getResolvableConstructor が IllegalStateException を投げる。"
                + "いずれも GlobalExceptionHandler に個別ハンドラが無く汎用ハンドラに落ちるため、"
                + "当該エンドポイントは入力内容によらず常に 500 になる。"
                + "chat.SendMessageRequest / bulletin.CreateThreadRequest で 2 度再発した事故であり、"
                + "Mock ベースの UT では原理的に検出できないため機械検出する。"
                + "是正の金型は chat.dto.SendMessageRequest（完全コンストラクタに @JsonCreator と "
                + "各引数の @JsonProperty を付与）")
            .as("request payload types must be constructible by their binder (D-7)");

    /**
     * 検査対象が空になっていないことの<b>自己健全性チェック</b>（番人の番人）。
     *
     * <p>D-7 の本体ルールは「検査対象集合に入った型」だけを見る。もし
     * {@link #requestPayloadBoundTypes(Collection)} が ArchUnit の API 変更・Controller 構造の
     * 変化などで<b>静かに空集合を返すようになった</b>場合、本体ルールは違反 0 件で<b>常に緑</b>になり、
     * 番人が死んだことに誰も気付けない（{@code memory/feedback_verify_tool_output_fabrication} と
     * 同じ「空＝合格」の罠）。それを防ぐため、走査結果が十分な規模であることを固定する。
     *
     * <p>閾値 500 の根拠: 2026-07-29 時点の実測で走査結果は <b>1085 型</b>（main の
     * {@code @RequestBody} 含有ソースは 500 本超）。閾値はその半分未満に置いた保守的な下限であり、
     * 「正常な削減」で割り込むことは想定しない。割り込んだ場合は走査ロジックの破損を疑うこと。
     */
    @ArchTest
    static void payload_bound_type_scan_must_not_silently_become_empty(JavaClasses classes) {
        int scanned = requestPayloadBoundTypes(classes).all().size();
        if (scanned < MIN_EXPECTED_PAYLOAD_BOUND_TYPES) {
            throw new AssertionError(String.format(
                "D-7 の検査対象（@RequestBody/@RequestPart/@ModelAttribute から到達する型）が "
                    + "%d 件しか見つからなかった（期待: %d 件以上）。"
                    + "走査ロジックが壊れて番人が「常に緑」になっている疑いが強い。"
                    + "requestPayloadBoundTypes() の Controller 走査・フィールド閉包を確認すること。",
                scanned, MIN_EXPECTED_PAYLOAD_BOUND_TYPES));
        }
    }

    // ------------------------------------------------------------------
    // 判定の単一正準（本番番人とメタテストが共有する）
    // ------------------------------------------------------------------

    /**
     * バインド経路別の検査対象集合。
     *
     * @param jsonBound {@code @RequestBody}/{@code @RequestPart} から到達する型（Jackson 判定）
     * @param formBound {@code @ModelAttribute}/無注釈複合型から到達する型（Spring バインダ判定）
     */
    record PayloadBoundTypes(Set<JavaClass> jsonBound, Set<JavaClass> formBound) {

        /** 両経路の和集合（走査規模の自己健全性チェック用）。 */
        Set<JavaClass> all() {
            Set<JavaClass> union = new LinkedHashSet<>(jsonBound);
            union.addAll(formBound);
            return union;
        }
    }

    /**
     * 全解析対象クラスから「リクエストペイロードとしてバインドされ得る型」を経路別に返す。
     *
     * <p>根は Controller の public Mapping メソッドの引数（JSON 経路＝{@code @RequestBody} /
     * {@code @RequestPart}、フォーム経路＝{@code @ModelAttribute} / 無注釈複合型）。
     * ジェネリクス入れ子は展開し、配列は要素型まで剥がしたうえで、フィールド型を推移的にたどって
     * {@code com.mannschaft.app} 配下のクラスへ閉包を取る。
     */
    static PayloadBoundTypes requestPayloadBoundTypes(Collection<JavaClass> allClasses) {
        Set<JavaClass> jsonRoots = new LinkedHashSet<>();
        Set<JavaClass> formRoots = new LinkedHashSet<>();
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
                    Set<JavaClass> target = null;
                    if (isJsonBodyParameter(parameter)) {
                        target = jsonRoots;
                    } else if (isFormBoundParameter(parameter)) {
                        target = formRoots;
                    }
                    if (target == null) {
                        continue;
                    }
                    for (JavaClass rawType : parameter.getType().getAllInvolvedRawTypes()) {
                        addIfAppClass(rawType, target);
                    }
                }
            }
        }
        return new PayloadBoundTypes(closureOf(jsonRoots), closureOf(formRoots));
    }

    /**
     * クラスが「Jackson から実体生成不能」＝ JSON 経路の D-7 違反かどうかを判定する。
     *
     * <p>判定は<b>十分条件のみ</b>を採る（偽陽性を出すと番人が信用されなくなるため）。
     * 詳細な根拠はクラス Javadoc の「違反条件」節を参照。
     */
    static boolean lacksUsableJacksonCreator(JavaClass clazz) {
        if (!isConcreteBindableClass(clazz) || isRecord(clazz)) {
            // record は正準コンストラクタが Jackson 2.12+ でネイティブ解決されるため対象外。
            // 実在例: village.dto.MeetupCreateRequest（record かつコンストラクタ 2 本で
            // @RequestBody にバインドされている）。除外しないとこれが偽陽性になる。
            return false;
        }
        if (hasOwnCustomDeserializerOrBuilder(clazz)) {
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

    /**
     * クラスが「Spring のフォームバインダから実体生成不能」＝フォーム経路の D-7 違反かを判定する。
     *
     * <p>{@code BeanUtils.getResolvableConstructor} は「Kotlin primary → 宣言コンストラクタが
     * ちょうど 1 本 → 引数無しコンストラクタ」の順にしか解決せず、<b>Jackson の注釈を一切見ない</b>。
     * よって {@code @JsonCreator} / {@code @JsonDeserialize} による免責は適用しない。
     * record も宣言コンストラクタが 2 本以上あれば解決に失敗するため除外しない。
     */
    static boolean lacksResolvableConstructor(JavaClass clazz) {
        if (!isConcreteBindableClass(clazz)) {
            return false;
        }
        Set<JavaConstructor> constructors = clazz.getConstructors();
        return constructors.size() >= 2 && !hasNoArgConstructor(constructors);
    }

    /** JSON 経路の違反 1 件分の説明文（本番番人とメタテストで文言を共有する）。 */
    static String jsonViolationMessage(JavaClass clazz) {
        return String.format(
            "%s is bound as a JSON request payload but Jackson cannot construct it: "
                + "it declares %d constructors %s, none annotated with @JsonCreator, "
                + "and it has no no-arg constructor. "
                + "Every request to the endpoint(s) taking this type will fail with HTTP 500 "
                + "(no suitable creator). "
                + "Fix: annotate the full constructor with @JsonCreator and each parameter with "
                + "@JsonProperty(\"...\"), following chat.dto.SendMessageRequest",
            clazz.getName(), clazz.getConstructors().size(), constructorSignatures(clazz));
    }

    /** フォーム経路の違反 1 件分の説明文。 */
    static String formViolationMessage(JavaClass clazz) {
        return String.format(
            "%s is bound as a form/@ModelAttribute payload but Spring cannot construct it: "
                + "it declares %d constructors %s and has no no-arg constructor, so "
                + "BeanUtils.getResolvableConstructor throws IllegalStateException and "
                + "every request to the endpoint(s) taking this type fails with HTTP 500. "
                + "Note @JsonCreator does NOT help here (the form binder ignores Jackson "
                + "annotations). Fix: add a no-arg constructor + setters, or reduce to a single "
                + "constructor",
            clazz.getName(), clazz.getConstructors().size(), constructorSignatures(clazz));
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static ArchCondition<JavaClass> beConstructibleByTheirBinder() {
        return new ArchCondition<>(
                "be constructible by their binder when bound as a request payload "
                    + "(@JsonCreator constructor/factory, or a no-arg constructor, "
                    + "or a single constructor)") {

            private Set<String> jsonBoundNames = Set.of();
            private Set<String> formBoundNames = Set.of();

            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                PayloadBoundTypes bound = requestPayloadBoundTypes(allObjectsToTest);
                jsonBoundNames = namesOf(bound.jsonBound());
                formBoundNames = namesOf(bound.formBound());
            }

            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                String name = clazz.getName();
                boolean json = jsonBoundNames.contains(name);
                boolean form = formBoundNames.contains(name);
                if (!json && !form) {
                    // どのバインダからも到達しない型は本ルールの対象外（イベントを立てない）。
                    return;
                }
                // フォーム経路の方が厳しい（Jackson 注釈が効かない）ため先に判定する。
                if (form && lacksResolvableConstructor(clazz)) {
                    events.add(SimpleConditionEvent.violated(clazz, formViolationMessage(clazz)));
                    return;
                }
                if (json && lacksUsableJacksonCreator(clazz)) {
                    events.add(SimpleConditionEvent.violated(clazz, jsonViolationMessage(clazz)));
                    return;
                }
                events.add(SimpleConditionEvent.satisfied(clazz,
                    name + " is constructible by its binder"));
            }
        };
    }

    private static Set<String> namesOf(Set<JavaClass> classes) {
        Set<String> names = new LinkedHashSet<>();
        for (JavaClass clazz : classes) {
            names.add(clazz.getName());
        }
        return names;
    }

    /** 根の集合からフィールド型を推移的にたどった閉包を返す。 */
    private static Set<JavaClass> closureOf(Set<JavaClass> roots) {
        Set<JavaClass> reachable = new LinkedHashSet<>(roots);
        Deque<JavaClass> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            JavaClass current = queue.removeFirst();
            for (JavaField field : current.getAllFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                for (JavaClass rawType : field.getType().getAllInvolvedRawTypes()) {
                    JavaClass element = unwrapArray(rawType);
                    if (isAppBindableClass(element) && reachable.add(element)) {
                        queue.addLast(element);
                    }
                }
            }
        }
        return reachable;
    }

    /** 引数に {@code @RequestBody} または {@code @RequestPart} が付いているか。 */
    private static boolean isJsonBodyParameter(JavaParameter parameter) {
        return parameter.isAnnotatedWith(REQUEST_BODY_ANNOTATION)
            || parameter.isAnnotatedWith(REQUEST_PART_ANNOTATION);
    }

    /**
     * 引数がフォームバインド（{@code @ModelAttribute}）の対象か。
     *
     * <p>{@code @ModelAttribute} が明示されている場合に加え、<b>アノテーションを一切持たない</b>
     * 引数も対象とする。Spring はこれを暗黙の {@code @ModelAttribute} として扱うためである。
     * {@code HttpServletRequest} / {@code Pageable} / {@code Authentication} のような
     * 引数リゾルバが処理する型もここに入るが、閉包は {@code com.mannschaft.app} 配下に
     * 限定しているため実害がない（実測でアプリパッケージの無注釈引数は 0 件）。
     */
    private static boolean isFormBoundParameter(JavaParameter parameter) {
        return parameter.isAnnotatedWith(MODEL_ATTRIBUTE_ANNOTATION)
            || parameter.getAnnotations().isEmpty();
    }

    /** アプリ配下のバインド可能クラスなら根の集合へ追加する（配列は要素型まで剥がす）。 */
    private static void addIfAppClass(JavaClass clazz, Set<JavaClass> roots) {
        JavaClass element = unwrapArray(clazz);
        if (isAppBindableClass(element)) {
            roots.add(element);
        }
    }

    /** 閉包へ入れてよい「アプリ配下の非 interface / 非 enum クラス」か。 */
    private static boolean isAppBindableClass(JavaClass clazz) {
        return clazz.getName().startsWith(APP_PACKAGE_PREFIX)
            && !clazz.isArray()
            && !clazz.isEnum()
            && !clazz.isInterface();
    }

    /** 配列型なら要素型まで剥がす（{@code Foo[][]} → {@code Foo}）。 */
    private static JavaClass unwrapArray(JavaClass clazz) {
        JavaClass current = clazz;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }

    /** バインド先になり得る具象クラスか（interface/abstract/enum/annotation/合成を除く）。 */
    private static boolean isConcreteBindableClass(JavaClass clazz) {
        if (clazz.isInterface() || clazz.isEnum() || clazz.isAnnotation() || clazz.isArray()) {
            return false;
        }
        if (clazz.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return false;
        }
        // 匿名クラス・ローカルクラス・合成クラスはバインド先にならない。
        return !clazz.getSimpleName().isEmpty() && !clazz.getName().contains("$$");
    }

    /** {@code java.lang.Record} を直接の親に持つか（ArchUnit 1.3 に record 述語が無いため名前判定）。 */
    private static boolean isRecord(JavaClass clazz) {
        return clazz.getRawSuperclass()
            .map(superclass -> "java.lang.Record".equals(superclass.getName()))
            .orElse(false);
    }

    /**
     * {@code @JsonDeserialize} で<b>クラス自身の生成手段</b>が与えられているか。
     *
     * <p>{@code using}（カスタム deserializer）と {@code builder}（builder 経由生成）だけを免責する。
     * {@code as} / {@code contentAs} / {@code keyAs} / {@code contentUsing} / {@code keyUsing} は
     * 別実装型や要素型の指定にすぎず、そのクラス自身の生成手段を与えないため免責しない。
     */
    private static boolean hasOwnCustomDeserializerOrBuilder(JavaClass clazz) {
        Optional<JavaAnnotation<JavaClass>> annotation =
            clazz.tryGetAnnotationOfType(JSON_DESERIALIZE_ANNOTATION);
        if (annotation.isEmpty()) {
            return false;
        }
        JavaAnnotation<JavaClass> jsonDeserialize = annotation.get();
        return isNonDefaultClassProperty(jsonDeserialize, "using", JSON_DESERIALIZER_NONE)
            || isNonDefaultClassProperty(jsonDeserialize, "builder", VOID_CLASS);
    }

    /** アノテーションの Class 型プロパティが既定値以外に設定されているか。 */
    private static boolean isNonDefaultClassProperty(
            JavaAnnotation<JavaClass> annotation, String property, String defaultTypeName) {
        return annotation.get(property)
            .filter(JavaClass.class::isInstance)
            .map(JavaClass.class::cast)
            .filter(value -> !defaultTypeName.equals(value.getName()))
            .isPresent();
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

    /** 違反メッセージ用のコンストラクタ引数型シグネチャ一覧（安定した順序で出す）。 */
    private static Set<String> constructorSignatures(JavaClass clazz) {
        return new TreeSet<>(clazz.getConstructors().stream()
            .map(JsonRequestBodyCreatorArchTest::constructorSignature)
            .toList());
    }

    /** 違反メッセージ用のコンストラクタ引数型シグネチャ（宣言順を保つ）。 */
    private static String constructorSignature(JavaConstructor constructor) {
        return constructor.getRawParameterTypes().stream()
            .map(JavaClass::getSimpleName)
            .toList()
            .toString();
    }
}
