package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-7 番人（{@link JsonRequestBodyCreatorArchTest}）の検出ロジックが
 * <b>偽陰性ゼロ</b>（Jackson が実体生成できない DTO を取り逃さない）かつ
 * <b>偽陽性ゼロ</b>（Jackson が扱える DTO・そもそもバインドされない DTO を誤検出しない）
 * であることを証明するメタテスト。
 *
 * <p>番人本体は {@code @AnalyzeClasses(importOptions = DoNotIncludeTests.class)} で test 配下を
 * 除外しており、本メタテストの fixture Controller/DTO は本番の D-7 解析へ混入しない。
 * 本テストは fixture パッケージだけを {@link ClassFileImporter} で読み込み、番人の
 * <b>合格判定の単一正準</b>である
 * {@link JsonRequestBodyCreatorArchTest#jsonBodyBoundTypes(java.util.Collection)} と
 * {@link JsonRequestBodyCreatorArchTest#lacksUsableJacksonCreator(JavaClass)} を
 * fixture 限定で評価する（判定ロジックの二重実装を避ける）。
 *
 * <h2>担保するケース</h2>
 * <table>
 *   <caption>fixture と期待</caption>
 *   <tr><th>fixture</th><th>構造</th><th>期待</th></tr>
 *   <tr><td>{@link D7PreFixCreateThreadRequestReplica}</td>
 *       <td>是正前 {@code CreateThreadRequest} と同型（2 ctor・creator 無し・no-arg 無し）</td>
 *       <td><b>検出（違反）</b>= 回帰固定</td></tr>
 *   <tr><td>{@link D7NestedBrokenAttachment}</td>
 *       <td>同上だが到達はフィールド型経由（{@code List<...>} 越し）</td>
 *       <td><b>検出（違反）</b>= 入れ子も見る</td></tr>
 *   <tr><td>{@link D7JsonCreatorRequest}</td><td>2 ctor ＋ {@code @JsonCreator}</td>
 *       <td>非検出</td></tr>
 *   <tr><td>{@link D7StaticFactoryCreatorRequest}</td>
 *       <td>2 ctor ＋ {@code @JsonCreator} static ファクトリ</td><td>非検出</td></tr>
 *   <tr><td>{@link D7NoArgsAndSettersRequest}</td>
 *       <td>Lombok {@code @Data + @NoArgsConstructor + @AllArgsConstructor}（2 ctor）</td>
 *       <td>非検出</td></tr>
 *   <tr><td>{@link D7SingleConstructorRequest}</td>
 *       <td>全 final だがコンストラクタ 1 本</td><td>非検出</td></tr>
 *   <tr><td>{@link D7UnboundBrokenRequest}</td>
 *       <td>壊れているがどこからもバインドされない</td>
 *       <td><b>検査対象外</b>= 命名でなく到達可能性で絞っている担保</td></tr>
 * </table>
 *
 * <p>さらに、Lombok が実際に生成するコンストラクタ本数を<b>main の実 DTO のバイトコード</b>から
 * 読み取って固定する（{@link #lombokGeneratedConstructorCountsArePinned()}）。
 * Lombok の生成物はソース上の注釈からは見えず<b>机上で本数を決めると誤る</b>ため、
 * 番人の前提を実測で固定しておく。なお本リポの test ソースセットには Lombok が入っていない
 * （{@code build.gradle.kts} で {@code compileOnly}/{@code annotationProcessor} のみ＝ main 限定）
 * ため、fixture は素の Java で書いている。
 */
@DisplayName("D-7 番人 Jackson creator 検出ロジックの偽陰性ゼロ・偽陽性ゼロ証明（メタテスト）")
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
    };

    private static JavaClasses fixtureClasses;

    private static JavaClasses productionClasses;

    /** fixture Controller の {@code @RequestBody}/{@code @RequestPart} から到達する型の名前集合。 */
    private static List<String> boundTypeNames;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
        productionClasses = new ClassFileImporter().importPackages(PRODUCTION_DTO_PACKAGES);
        Set<JavaClass> bound = JsonRequestBodyCreatorArchTest.jsonBodyBoundTypes(fixtureClasses);
        boundTypeNames = bound.stream().map(JavaClass::getName).toList();
    }

    // ------------------------------------------------------------------
    // 偽陰性ゼロ（壊れた DTO を確実に検出する）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回帰固定: 是正前CreateThreadRequestと同型のDTOは違反として検出される")
    void preFixCreateThreadRequestReplicaIsDetected() {
        assertThat(boundTypeNames)
            .as("@RequestBody 引数型は検査対象に入るべき")
            .contains(D7PreFixCreateThreadRequestReplica.class.getName());
        assertThat(violates(D7PreFixCreateThreadRequestReplica.class))
            .as("複数コンストラクタ・@JsonCreator 無し・引数無しコンストラクタ無しの DTO は "
                + "Jackson が実体生成できず常時 500 になるため、D-7 で検出されるべき")
            .isTrue();
    }

    @Test
    @DisplayName("入れ子: List<Root>のフィールド越しに到達する壊れたDTOも違反として検出される")
    void nestedBrokenAttachmentIsDetected() {
        assertThat(boundTypeNames)
            .as("バインドされる型のフィールド型（ジェネリクス引数越し）も検査対象に入るべき")
            .contains(D7RootRequest.class.getName(), D7NestedBrokenAttachment.class.getName());
        assertThat(violates(D7NestedBrokenAttachment.class))
            .as("入れ子 DTO も同じく no suitable creator で親ごと 500 にするため検出されるべき")
            .isTrue();
    }

    // ------------------------------------------------------------------
    // 偽陽性ゼロ（Jackson が扱える DTO を誤検出しない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("金型: @JsonCreator付きコンストラクタを持つDTOは検出されない")
    void jsonCreatorConstructorNotDetected() {
        assertThat(violates(D7JsonCreatorRequest.class))
            .as("是正の金型（SendMessageRequest と同型）は D-7 で検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("@JsonCreator付きstaticファクトリを持つDTOは検出されない")
    void jsonCreatorStaticFactoryNotDetected() {
        assertThat(violates(D7StaticFactoryCreatorRequest.class))
            .as("static ファクトリ creator も Jackson は採用するため検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("引数無しコンストラクタ+setterのDTOは検出されない（コンストラクタ本数だけで判定していない）")
    void noArgsAndSettersNotDetected() {
        assertThat(violates(D7NoArgsAndSettersRequest.class))
            .as("引数無しコンストラクタがあれば Jackson は既定 creator で実体生成できるため、"
                + "コンストラクタが 2 本でも検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("コンストラクタ1本のDTOは全フィールドfinalでも検出されない（-parametersで暗黙creator）")
    void singleConstructorNotDetected() {
        assertThat(violates(D7SingleConstructorRequest.class))
            .as("唯一の引数付きコンストラクタは ParameterNamesModule で暗黙 creator になるため、"
                + "全フィールド final でも検出されてはならない")
            .isFalse();
    }

    @Test
    @DisplayName("バインドされないDTOは壊れていても検査対象外（命名でなく到達可能性で絞っている）")
    void unboundBrokenRequestIsOutOfScope() {
        assertThat(boundTypeNames)
            .as("どの Controller からも到達しない型は検査対象に入ってはならない"
                + "（*Request 命名で絞ると誤検出になる）")
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
     *       {@code @Builder} は static {@code builder()} を足すだけでコンストラクタは増やさない。
     *       引数無しコンストラクタがあるため {@code @JsonCreator} 無しでも合格。</li>
     * </ul>
     */
    @Test
    @DisplayName("Lombok実測: @RequiredArgsConstructorは1本・@NoArgs+@AllArgs(+@Builder)は2本を生成する")
    void lombokGeneratedConstructorCountsArePinned() {
        JavaClass requiredArgs = productionClasses.get(
            com.mannschaft.app.activity.dto.AddParticipantsRequest.class);
        assertThat(requiredArgs.getConstructors())
            .as("@Getter + @RequiredArgsConstructor は全 final フィールドの 1 本だけを生成する")
            .hasSize(1);

        JavaClass noArgsAllArgs = productionClasses.get(
            com.mannschaft.app.cspreport.dto.CspReportRequest.class);
        assertThat(noArgsAllArgs.getConstructors())
            .as("@Builder + @NoArgsConstructor + @AllArgsConstructor は 2 本（@Builder は"
                + "コンストラクタを増やさず static builder() を足すだけ）")
            .hasSize(2);
        assertThat(noArgsAllArgs.getConstructors().stream()
            .anyMatch(constructor -> constructor.getRawParameterTypes().isEmpty()))
            .as("@NoArgsConstructor が引数無しコンストラクタを生成していること")
            .isTrue();
    }

    /**
     * 既知の「良い例」が本番コードで実際に合格することの裏取り。
     *
     * <p>本番ルール（{@link JsonRequestBodyCreatorArchTest}）が全 main クラスを検査するため
     * 本来は冗長だが、<b>是正の金型そのもの</b>と、<b>2 度目の再発事例</b>、および
     * Lombok 様式の代表を名指しで固定しておくことで、将来ルールを緩めた際に
     * 「金型が検出される／されない」の回帰が明示的に落ちるようにする。
     */
    @Test
    @DisplayName("既知の良い例（SendMessageRequest/CreateThreadRequest/CreatePostRequest等）は検出されない")
    void knownGoodProductionDtosAreNotDetected() {
        assertThat(violatesInProduction(com.mannschaft.app.chat.dto.SendMessageRequest.class))
            .as("是正の金型（@JsonCreator + @JsonProperty）は検出されてはならない")
            .isFalse();
        assertThat(violatesInProduction(com.mannschaft.app.bulletin.dto.CreateThreadRequest.class))
            .as("PR #2503 で是正済みの CreateThreadRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesInProduction(com.mannschaft.app.timeline.dto.CreatePostRequest.class))
            .as("コンストラクタ 3 本 + @JsonCreator の CreatePostRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesInProduction(com.mannschaft.app.todo.dto.TodoStatusChangeRequest.class))
            .as("コンストラクタ 2 本 + @JsonCreator の TodoStatusChangeRequest は検出されてはならない")
            .isFalse();
        assertThat(violatesInProduction(
                com.mannschaft.app.activity.dto.AddParticipantsRequest.class))
            .as("@RequiredArgsConstructor（1 本）の DTO は検出されてはならない")
            .isFalse();
        assertThat(violatesInProduction(com.mannschaft.app.cspreport.dto.CspReportRequest.class))
            .as("@NoArgsConstructor + @AllArgsConstructor（2 本）の DTO は検出されてはならない")
            .isFalse();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** fixture クラスが D-7 違反（Jackson から実体生成不能）と判定されるか。 */
    private static boolean violates(Class<?> clazz) {
        return JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
            fixtureClasses.get(clazz));
    }

    /** main の実クラスが D-7 違反と判定されるか。 */
    private static boolean violatesInProduction(Class<?> clazz) {
        return JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
            productionClasses.get(clazz));
    }
}
