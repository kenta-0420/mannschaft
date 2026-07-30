package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.JsonRequestBodyCreatorArchTest.PayloadBoundTypes;
import com.mannschaft.app.common.architecture.fixtures.D7ArrayElementBrokenItem;
import com.mannschaft.app.common.architecture.fixtures.D7ContentDeserializeBrokenRequest;
import com.mannschaft.app.common.architecture.fixtures.D7CustomDeserializerRequest;
import com.mannschaft.app.common.architecture.fixtures.D7FormBrokenSearchRequest;
import com.mannschaft.app.common.architecture.fixtures.D7FormOkSearchRequest;
import com.mannschaft.app.common.architecture.fixtures.D7JsonCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7NestedBrokenAttachment;
import com.mannschaft.app.common.architecture.fixtures.D7NoArgsAndSettersRequest;
import com.mannschaft.app.common.architecture.fixtures.D7PreFixCreateThreadRequestReplica;
import com.mannschaft.app.common.architecture.fixtures.D7RootRequest;
import com.mannschaft.app.common.architecture.fixtures.D7SingleConstructorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7StaticFactoryCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7UnboundBrokenRequest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-7 番人（{@link JsonRequestBodyCreatorArchTest}）の検出ロジックが
 * <b>偽陰性ゼロ</b>（バインダが実体生成できない DTO を取り逃さない）かつ
 * <b>偽陽性ゼロ</b>（バインダが扱える DTO・そもそもバインドされない DTO を誤検出しない）
 * であることを証明するメタテスト。
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test 配下を
 * 除外しており、本メタテストの fixture Controller/DTO は本番の D-7 解析へ混入しない。
 * 本テストは fixture パッケージだけを {@link ClassFileImporter} で読み込み、番人の
 * <b>合格判定の単一正準</b>である
 * {@link JsonRequestBodyCreatorArchTest#requestPayloadBoundTypes(java.util.Collection)} /
 * {@link JsonRequestBodyCreatorArchTest#lacksUsableJacksonCreator(JavaClass)} /
 * {@link JsonRequestBodyCreatorArchTest#lacksResolvableConstructor(JavaClass)} を
 * fixture 限定で評価する（判定ロジックの二重実装を避ける）。
 *
 * <h2>担保するケース</h2>
 * <table>
 *   <caption>fixture と期待</caption>
 *   <tr><th>fixture</th><th>構造</th><th>期待</th></tr>
 *   <tr><td>{@link D7PreFixCreateThreadRequestReplica}</td>
 *       <td>是正前 {@code CreateThreadRequest} と同型（2 ctor・creator 無し・no-arg 無し）</td>
 *       <td><b>検出</b>＝回帰固定</td></tr>
 *   <tr><td>{@link D7NestedBrokenAttachment}</td>
 *       <td>同上だが到達は {@code List<...>} フィールド越し</td>
 *       <td><b>検出</b>＝入れ子も見る</td></tr>
 *   <tr><td>{@link D7ArrayElementBrokenItem}</td>
 *       <td>同上だが到達は<b>配列</b>フィールド越し</td>
 *       <td><b>検出</b>＝配列の要素型まで剥がす</td></tr>
 *   <tr><td>{@link D7ContentDeserializeBrokenRequest}</td>
 *       <td>壊れているが {@code @JsonDeserialize(contentUsing = ...)} が付く</td>
 *       <td><b>検出</b>＝自身の生成手段でない指定は免責しない</td></tr>
 *   <tr><td>{@link D7FormBrokenSearchRequest}</td>
 *       <td>{@code @ModelAttribute} ＋ 2 ctor ＋ no-arg 無し（{@code @JsonCreator} あり）</td>
 *       <td><b>検出</b>＝フォーム経路は Jackson 注釈で救われない</td></tr>
 *   <tr><td>{@link D7JsonCreatorRequest}</td><td>2 ctor ＋ {@code @JsonCreator}</td>
 *       <td>非検出</td></tr>
 *   <tr><td>{@link D7StaticFactoryCreatorRequest}</td>
 *       <td>2 ctor ＋ {@code @JsonCreator} static ファクトリ</td><td>非検出</td></tr>
 *   <tr><td>{@link D7CustomDeserializerRequest}</td>
 *       <td>壊れているが {@code @JsonDeserialize(using = ...)}</td><td>非検出</td></tr>
 *   <tr><td>{@link D7NoArgsAndSettersRequest}</td>
 *       <td>引数無し ＋ 全引数 ＋ setter（2 ctor）</td><td>非検出</td></tr>
 *   <tr><td>{@link D7SingleConstructorRequest}</td>
 *       <td>全 final だがコンストラクタ 1 本</td><td>非検出</td></tr>
 *   <tr><td>{@link D7FormOkSearchRequest}</td>
 *       <td>{@code @ModelAttribute} ＋ 引数無し ＋ setter</td><td>非検出</td></tr>
 *   <tr><td>{@link D7UnboundBrokenRequest}</td>
 *       <td>壊れているがどこからもバインドされない</td>
 *       <td><b>検査対象外</b>＝命名でも形状でもなく到達可能性で絞っている担保</td></tr>
 * </table>
 *
 * <p>さらに、Lombok が実際に生成するコンストラクタ本数を<b>main の実 DTO のバイトコード</b>から
 * 読み取って固定する（{@link #lombokGeneratedConstructorCountsArePinned()}）。
 * Lombok の生成物はソース上の注釈からは見えず<b>机上で本数を決めると誤る</b>ため、
 * 番人の前提を実測で固定しておく。なお本リポの test ソースセットには Lombok が入っていない
 * （{@code build.gradle.kts} で {@code compileOnly}/{@code annotationProcessor} のみ＝ main 限定）
 * ため、fixture は素の Java で書いている。
 */
@DisplayName("D-7 番人 バインダ実体生成可能性 検出ロジックの偽陰性ゼロ・偽陽性ゼロ証明（メタテスト）")
class JsonRequestBodyCreatorConditionTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    /** Lombok 生成物の実測固定・既知の良い例の裏取りに使う main 側 DTO パッケージ。 */
    private static final String[] PRODUCTION_DTO_PACKAGES = {
        "com.mannschaft.app.chat.dto",
        "com.mannschaft.app.bulletin.dto",
        "com.mannschaft.app.timeline.dto",
        "com.mannschaft.app.todo.dto",
        "com.mannschaft.app.activity.dto",
        "com.mannschaft.app.cspreport.dto",
        "com.mannschaft.app.village.dto",
        "com.mannschaft.app.recruitment.dto",
    };

    private static JavaClasses fixtureClasses;

    private static JavaClasses productionClasses;

    /** fixture Controller の JSON 経路（{@code @RequestBody}/{@code @RequestPart}）到達型。 */
    private static List<String> jsonBoundNames;

    /** fixture Controller のフォーム経路（{@code @ModelAttribute}）到達型。 */
    private static List<String> formBoundNames;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
        productionClasses = new ClassFileImporter().importPackages(PRODUCTION_DTO_PACKAGES);
        PayloadBoundTypes bound =
            JsonRequestBodyCreatorArchTest.requestPayloadBoundTypes(fixtureClasses);
        jsonBoundNames = bound.jsonBound().stream().map(JavaClass::getName).toList();
        formBoundNames = bound.formBound().stream().map(JavaClass::getName).toList();
    }

    // ------------------------------------------------------------------
    // 偽陰性ゼロ（壊れた DTO を確実に検出する）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回帰固定: 是正前CreateThreadRequestと同型のDTOは違反として検出される")
    void preFixCreateThreadRequestReplicaIsDetected() {
        assertThat(jsonBoundNames)
            .as("@RequestBody 引数型は JSON 経路の検査対象に入るべき")
            .contains(D7PreFixCreateThreadRequestReplica.class.getName());
        assertThat(violatesJson(D7PreFixCreateThreadRequestReplica.class))
            .as("複数コンストラクタ・@JsonCreator 無し・引数無しコンストラクタ無しの DTO は "
                + "Jackson が実体生成できず常時 500 になるため、D-7 で検出されるべき")
            .isTrue();
    }

    @Test
    @DisplayName("入れ子(List): List<Root>のフィールド越しに到達する壊れたDTOも検出される")
    void nestedBrokenAttachmentIsDetected() {
        assertThat(jsonBoundNames)
            .as("バインドされる型のフィールド型（ジェネリクス引数越し）も検査対象に入るべき")
            .contains(D7RootRequest.class.getName(), D7NestedBrokenAttachment.class.getName());
        assertThat(violatesJson(D7NestedBrokenAttachment.class))
            .as("入れ子 DTO も同じく no suitable creator で親ごと 500 にするため検出されるべき")
            .isTrue();
    }

    @Test
    @DisplayName("入れ子(配列): Foo[]フィールドの要素型に到達する壊れたDTOも検出される")
    void arrayElementBrokenItemIsDetected() {
        assertThat(jsonBoundNames)
            .as("配列フィールドは要素型まで剥がして閉包に入れるべき"
                + "（isArray() で捨てると Foo[] の Foo に届かない）")
            .contains(D7ArrayElementBrokenItem.class.getName());
        assertThat(violatesJson(D7ArrayElementBrokenItem.class))
            .as("配列越しに到達する入れ子 DTO も検出されるべき")
            .isTrue();
    }

    @Test
    @DisplayName("抜け道封じ: @JsonDeserialize(contentUsing=)だけでは免責されず検出される")
    void contentDeserializeOnlyIsStillDetected() {
        assertThat(jsonBoundNames)
            .contains(D7ContentDeserializeBrokenRequest.class.getName());
        assertThat(violatesJson(D7ContentDeserializeBrokenRequest.class))
            .as("contentUsing / contentAs / keyAs / as はクラス自身の生成手段を与えないため、"
                + "@JsonDeserialize が付いているというだけで免責してはならない")
            .isTrue();
    }

    @Test
    @DisplayName("フォーム経路: @ModelAttributeのDTOは@JsonCreatorがあっても検出される")
    void formBoundBrokenRequestIsDetected() {
        assertThat(formBoundNames)
            .as("@ModelAttribute 引数型はフォーム経路の検査対象に入るべき")
            .contains(D7FormBrokenSearchRequest.class.getName());
        assertThat(JsonRequestBodyCreatorArchTest.lacksResolvableConstructor(
                fixtureClasses.get(D7FormBrokenSearchRequest.class)))
            .as("BeanUtils.getResolvableConstructor は Jackson 注釈を見ないため、"
                + "@JsonCreator があっても 2 ctor + no-arg 無しなら IllegalStateException で 500 になる")
            .isTrue();
    }

    // ------------------------------------------------------------------
    // 偽陽性ゼロ（バインダが扱える DTO を誤検出しない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金型: @JsonCreator付きコンストラクタを持つDTOは検出されない")
    void jsonCreatorConstructorNotDetected() {
        assertThat(violatesJson(D7JsonCreatorRequest.class))
            .as("是正の金型（SendMessageRequest と同型）は D-7 で検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("@JsonCreator付きstaticファクトリを持つDTOは検出されない")
    void jsonCreatorStaticFactoryNotDetected() {
        assertThat(violatesJson(D7StaticFactoryCreatorRequest.class))
            .as("static ファクトリ creator も Jackson は採用するため検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("@JsonDeserialize(using=)でクラス自身の生成手段があるDTOは検出されない")
    void customDeserializerNotDetected() {
        assertThat(violatesJson(D7CustomDeserializerRequest.class))
            .as("using はクラス自身の生成手段なので免責されるべき")
            .isFalse();
    }

    @Test
    @DisplayName("引数無しコンストラクタ+setterのDTOは検出されない（コンストラクタ本数だけで判定していない）")
    void noArgsAndSettersNotDetected() {
        assertThat(violatesJson(D7NoArgsAndSettersRequest.class))
            .as("引数無しコンストラクタがあれば Jackson は既定 creator で実体生成できるため、"
                + "コンストラクタが 2 本でも検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("コンストラクタ1本のDTOは全フィールドfinalでも検出されない（-parametersで暗黙creator）")
    void singleConstructorNotDetected() {
        assertThat(violatesJson(D7SingleConstructorRequest.class))
            .as("唯一の引数付きコンストラクタは ParameterNamesModule で暗黙 creator になるため、"
                + "全フィールド final でも検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("フォーム経路: 引数無しコンストラクタ+setterの@ModelAttribute DTOは検出されない")
    void formBoundOkRequestNotDetected() {
        assertThat(formBoundNames).contains(D7FormOkSearchRequest.class.getName());
        assertThat(JsonRequestBodyCreatorArchTest.lacksResolvableConstructor(
                fixtureClasses.get(D7FormOkSearchRequest.class)))
            .as("BeanUtils.getResolvableConstructor は引数無しコンストラクタを解決できる"
                + "（main の RecruitmentListingSearchRequest と同型）")
            .isFalse();
    }

    @Test
    @DisplayName("バインドされないDTOは壊れていても検査対象外（命名でも形状でもなく到達可能性で絞っている）")
    void unboundBrokenRequestIsOutOfScope() {
        assertThat(jsonBoundNames)
            .as("どの Controller からも到達しない型は検査対象に入ってはならない"
                + "（*Request 命名や形状だけで絞ると誤検出になる）")
            .doesNotContain(D7UnboundBrokenRequest.class.getName());
        assertThat(formBoundNames)
            .doesNotContain(D7UnboundBrokenRequest.class.getName());
    }

    // ------------------------------------------------------------------
    // Lombok 生成コンストラクタ本数の実測固定（main の実 DTO で裏取りする）
    // ------------------------------------------------------------------

    /**
     * <b>Lombok が実際に何本のコンストラクタを生成するか</b>をバイトコードで固定する。
     *
     * <p>Lombok の生成物はソース上の注釈からは見えず、<b>机上で本数を決めると誤る</b>。
     * かつ本リポの test ソースセットには Lombok が入っていない（{@code build.gradle.kts} は
     * {@code compileOnly}/{@code annotationProcessor} のみ＝ main 限定）ため、
     * fixture では Lombok を再現できない。よって<b>実際に Lombok でビルドされる main の実 DTO</b>
     * に対して番人の前提を固定する。
     *
     * <p>固定する事実（{@code javap -p} での実測とも一致）:
     * <ul>
     *   <li>{@code @Getter + @RequiredArgsConstructor}（{@code AddParticipantsRequest}）
     *       → コンストラクタ <b>1 本</b>。{@code @JsonCreator} 無しでも合格。</li>
     *   <li>{@code @Getter + @Builder + @NoArgsConstructor + @AllArgsConstructor}
     *       （{@code CspReportRequest}）→ コンストラクタ <b>2 本</b>（引数無し＋全引数）。
     *       {@code @Builder} は static {@code builder()} を足すだけでコンストラクタは増やさない。</li>
     * </ul>
     *
     * <p><b>この 2 クラス自体が壊れているわけではない</b>。本テストが落ちた場合、まず疑うべきは
     * 「これらの DTO に後方互換コンストラクタが追加された」ことであり、その場合は
     * <b>同じ様式の別 DTO に差し替えるか本数の期待値を更新すればよい</b>（D-7 番人本体の合否とは無関係）。
     */
    @Test
    @DisplayName("Lombok実測: @RequiredArgsConstructorは1本・@NoArgs+@AllArgs(+@Builder)は2本を生成する")
    void lombokGeneratedConstructorCountsArePinned() {
        String note = "【この失敗は当該 DTO が壊れていることを意味しない】"
            + "本テストは Lombok の生成コンストラクタ本数という番人の前提を実測固定しているだけである。"
            + "対象 DTO に後方互換コンストラクタが追加された場合は、同じ Lombok 様式の別 DTO に"
            + "差し替えるか期待本数を更新すること。D-7 番人本体の合否とは無関係。 ";

        JavaClass requiredArgs = productionClasses.get(
            com.mannschaft.app.activity.dto.AddParticipantsRequest.class);
        assertThat(requiredArgs.getConstructors())
            .as(note + "@Getter + @RequiredArgsConstructor は全 final フィールドの 1 本だけを生成する")
            .hasSize(1);

        JavaClass noArgsAllArgs = productionClasses.get(
            com.mannschaft.app.cspreport.dto.CspReportRequest.class);
        assertThat(noArgsAllArgs.getConstructors())
            .as(note + "@Builder + @NoArgsConstructor + @AllArgsConstructor は 2 本"
                + "（@Builder はコンストラクタを増やさず static builder() を足すだけ）")
            .hasSize(2);
        assertThat(noArgsAllArgs.getConstructors().stream()
            .anyMatch(constructor -> constructor.getRawParameterTypes().isEmpty()))
            .as(note + "@NoArgsConstructor が引数無しコンストラクタを生成していること")
            .isTrue();
    }

    /**
     * 既知の「良い例」が本番コードで実際に合格することの裏取り。
     *
     * <p>本番ルール（{@link JsonRequestBodyCreatorArchTest}）が全 main クラスを検査するため
     * 本来は冗長だが、<b>是正の金型そのもの</b>と、<b>2 度目の再発事例</b>、および
     * <b>record 除外が load-bearing であることの実在例</b>を名指しで固定しておくことで、
     * 将来ルールを変えた際に回帰が明示的に落ちるようにする。
     */
    @Test
    @DisplayName("既知の良い例（SendMessageRequest/CreateThreadRequest/record/フォームDTO等）は検出されない")
    void knownGoodProductionDtosAreNotDetected() {
        assertThat(violatesJsonInProduction(com.mannschaft.app.chat.dto.SendMessageRequest.class))
            .as("是正の金型（@JsonCreator + @JsonProperty）は検出されてはならない")
            .isFalse();
        assertThat(violatesJsonInProduction(
                com.mannschaft.app.bulletin.dto.CreateThreadRequest.class))
            .as("PR #2503 で是正済みの CreateThreadRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesJsonInProduction(
                com.mannschaft.app.timeline.dto.CreatePostRequest.class))
            .as("コンストラクタ 3 本 + @JsonCreator の CreatePostRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesJsonInProduction(
                com.mannschaft.app.todo.dto.TodoStatusChangeRequest.class))
            .as("コンストラクタ 2 本 + @JsonCreator の TodoStatusChangeRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesJsonInProduction(
                com.mannschaft.app.activity.dto.AddParticipantsRequest.class))
            .as("@RequiredArgsConstructor（1 本）の DTO は検出されてはならない")
            .isFalse();
        assertThat(violatesJsonInProduction(
                com.mannschaft.app.cspreport.dto.CspReportRequest.class))
            .as("@NoArgsConstructor + @AllArgsConstructor（2 本）の DTO は検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("record除外はload-bearing: MeetupCreateRequestはrecordかつ2ctorで@RequestBodyに実在する")
    void recordWithTwoConstructorsIsNotDetected() {
        JavaClass meetupCreateRequest = productionClasses.get(
            com.mannschaft.app.village.dto.MeetupCreateRequest.class);
        assertThat(meetupCreateRequest.getConstructors())
            .as("この record は実際にコンストラクタを 2 本持つ（record 除外を外すと偽陽性になる実在例）")
            .hasSizeGreaterThanOrEqualTo(2);
        assertThat(JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(meetupCreateRequest))
            .as("record は正準コンストラクタが Jackson 2.12+ でネイティブ解決されるため"
                + "検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("フォーム経路の実在例: RecruitmentListingSearchRequestは検出されない")
    void productionFormBoundRequestIsNotDetected() {
        assertThat(JsonRequestBodyCreatorArchTest.lacksResolvableConstructor(
                productionClasses.get(
                    com.mannschaft.app.recruitment.dto.RecruitmentListingSearchRequest.class)))
            .as("main で唯一の @ModelAttribute DTO。@Getter @Setter ＋暗黙 no-arg で合格するはず")
            .isFalse();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** fixture クラスが JSON 経路の D-7 違反（Jackson から実体生成不能）と判定されるか。 */
    private static boolean violatesJson(Class<?> clazz) {
        return JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
            fixtureClasses.get(clazz));
    }

    /** main の実クラスが JSON 経路の D-7 違反と判定されるか。 */
    private static boolean violatesJsonInProduction(Class<?> clazz) {
        return JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
            productionClasses.get(clazz));
    }
}
