package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.mannschaft.app.common.architecture.fixtures.D7ContentDeserializeBrokenRequest;
import com.mannschaft.app.common.architecture.fixtures.D7CustomDeserializerRequest;
import com.mannschaft.app.common.architecture.fixtures.D7DelegatingAndPropertiesCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7DisabledModeCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7DisabledPlusFallbackCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7DualJsonCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7JsonCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7NoArgsAndSettersRequest;
import com.mannschaft.app.common.architecture.fixtures.D7NoArgsPlusDualCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7PreFixCreateThreadRequestReplica;
import com.mannschaft.app.common.architecture.fixtures.D7SingleConstructorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7StaticFactoryCreatorRequest;
import com.mannschaft.app.common.architecture.fixtures.D7ValidOnlyBoundRequest;
import com.mannschaft.app.config.JacksonConfig;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * D-7 番人（{@link JsonRequestBodyCreatorArchTest}）の<b>構造条件が実際の壊れ方と一致していること</b>を
 * 本番同等設定の実 {@link ObjectMapper} で実測して固定するテスト。
 *
 * <h2>なぜこのテストが要るのか</h2>
 * <p>番人は「{@code @JsonCreator} が付いているか」「コンストラクタが何本か」という<b>構造</b>しか見ない。
 * その構造条件が本当に「Jackson が実体を作れない形」と対応しているかは、
 * <b>実際にデシリアライズを走らせない限り誰も確かめていない</b>。
 * 機構を実測せずに机上で断定して誤った改修（過検出・見逃し）に至った失敗が
 * 過去に複数回あるため、番人の判定と実挙動を 1 対 1 で突き合わせる。
 *
 * <h2>証明する 2 方向</h2>
 * <ul>
 *   <li><b>番人が弾く形は本当に壊れる</b>: {@code readValue} が
 *       {@link InvalidDefinitionException} を投げること（＝ Spring では
 *       {@code HttpMessageConversionException} に包まれ常時 500 になる形）</li>
 *   <li><b>番人が通す形は本当に往復できる</b>: {@code readValue} が成功し値が入ること</li>
 * </ul>
 *
 * <p>{@link ObjectMapper} は {@link JacksonConfig#objectMapper(Jackson2ObjectMapperBuilder)} を
 * そのまま呼んで作る（{@code ParameterNamesModule} 登録などの本番設定を再現しないと
 * 「単一コンストラクタは暗黙 creator」の前提ごと検証が無意味になるため、素の
 * {@code new ObjectMapper()} は使わない）。
 */
@DisplayName("D-7 番人 構造条件と実 ObjectMapper の挙動の一致証明（実デシリアライズ）")
class JsonRequestBodyCreatorRuntimeProofTest {

    private static final String FIXTURES_PACKAGE =
        "com.mannschaft.app.common.architecture.fixtures";

    /** 本番と同じ設定の ObjectMapper（JacksonConfig をそのまま呼ぶ）。 */
    private static final ObjectMapper OBJECT_MAPPER =
        new JacksonConfig().objectMapper(Jackson2ObjectMapperBuilder.json());

    private static JavaClasses fixtureClasses;

    @BeforeAll
    static void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    // ------------------------------------------------------------------
    // 番人が弾く形は本当に壊れる（＝偽陽性ではない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("実測: 複数ctor+creator無し+no-arg無しはInvalidDefinitionExceptionで落ちる（番人も弾く）")
    void multipleConstructorsWithoutCreatorReallyFails() {
        assertBothRejected(D7PreFixCreateThreadRequestReplica.class,
            "{\"categoryId\":1,\"title\":\"t\",\"body\":\"b\"}");
    }

    @Test
    @DisplayName("実測: @JsonCreator二重付与はInvalidDefinitionExceptionで落ちる（番人も弾く）")
    void duplicatePropertiesCreatorsReallyFail() {
        assertBothRejected(D7DualJsonCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\",\"body\":\"b\"}");
    }

    /**
     * #2613 是正の裏取り: 引数無しコンストラクタがあっても properties-based creator が
     * 2 本あれば実際に {@link InvalidDefinitionException} で落ちることの実測。
     *
     * <p>是正前の {@code lacksUsableJacksonCreator} は no-arg コンストラクタの有無による
     * 早期 return が二重 creator の検査より先にあり、この形を合格と誤判定していた
     * （＝偽陰性）。ここで実際に壊れることを確認し、是正後の判定と一致させる。
     */
    @Test
    @DisplayName("実測(#2613): no-argコンストラクタありでもproperties-based creator2本はInvalidDefinitionExceptionで落ちる")
    void noArgsConstructorDoesNotRescueDuplicatePropertiesCreators() {
        assertBothRejected(D7NoArgsPlusDualCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\",\"body\":\"b\"}");
    }

    @Test
    @DisplayName("実測: 唯一のctorが@JsonCreator(mode=DISABLED)なら暗黙creatorも断たれて落ちる（番人も弾く）")
    void disabledModeCreatorReallyFails() {
        assertBothRejected(D7DisabledModeCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\"}");
    }

    /**
     * 実測で分かった<b>反直感的な挙動</b>の固定:
     * コンストラクタ 2 本のうち片方だけが {@code DISABLED} の場合、
     * 打ち消しによって候補が 1 本に絞られ<b>正常に往復する</b>。
     *
     * <p>「{@code DISABLED} が付いていたら違反」と机上で決めるとこの形を誤検出する。
     * 番人が「打ち消し後に残る候補の本数」で判定していることの裏取り。
     */
    @Test
    @DisplayName("実測: 2ctorのうち片方だけDISABLEDなら残る1本が暗黙creatorになり往復できる（番人も通す）")
    void disabledOnOneOfTwoConstructorsStillDeserializes() {
        assertBothAccepted(D7DisabledPlusFallbackCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\"}");
    }

    @Test
    @DisplayName("実測: @JsonDeserialize(contentUsing=)だけでは救われず落ちる（番人も弾く）")
    void contentUsingOnlyReallyFails() {
        assertBothRejected(D7ContentDeserializeBrokenRequest.class,
            "{\"title\":\"t\"}");
    }

    // ------------------------------------------------------------------
    // 番人が通す形は本当に往復できる（＝偽陰性ではない側の裏取り）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("実測: @JsonCreator付きコンストラクタのDTOは往復できる（番人も通す）")
    void jsonCreatorConstructorReallyDeserializes() {
        assertBothAccepted(D7JsonCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\",\"body\":\"b\"}");
    }

    @Test
    @DisplayName("実測: @JsonCreator付きstaticファクトリのDTOは往復できる（番人も通す）")
    void staticFactoryCreatorReallyDeserializes() {
        assertBothAccepted(D7StaticFactoryCreatorRequest.class, "{\"title\":\"t\"}");
    }

    @Test
    @DisplayName("実測: 引数無しコンストラクタ+setterのDTOは往復できる（番人も通す）")
    void noArgsAndSettersReallyDeserializes() {
        assertBothAccepted(D7NoArgsAndSettersRequest.class, "{\"title\":\"t\"}");
    }

    @Test
    @DisplayName("実測: 全final・コンストラクタ1本のDTOは往復できる（ParameterNamesModuleの前提固定）")
    void singleConstructorReallyDeserializes() {
        assertBothAccepted(D7SingleConstructorRequest.class, "{\"title\":\"t\"}");
    }

    @Test
    @DisplayName("実測: delegating+properties creatorの共存は往復できる（番人が誤検出しないこと）")
    void delegatingAndPropertiesCreatorsReallyDeserialize() {
        assertBothAccepted(D7DelegatingAndPropertiesCreatorRequest.class,
            "{\"categoryId\":1,\"title\":\"t\"}");
    }

    /**
     * {@code @JsonDeserialize(using = ...)} が「クラス自身の生成手段」であることの実測。
     *
     * <p>fixture の deserializer は本文で {@code UnsupportedOperationException} を投げる
     * ダミーだが、<b>そこへ到達すること自体</b>が「Jackson がデシリアライザの構築に成功し、
     * creator 不在で落ちてはいない」ことの証明になる（構築に失敗するなら
     * {@code InvalidDefinitionException} が先に出る）。
     */
    @Test
    @DisplayName("実測: @JsonDeserialize(using=)のDTOはデシリアライザ構築に成功する（番人も通す）")
    void customDeserializerReallyResolvesDeserializer() {
        assertThatThrownBy(() ->
            OBJECT_MAPPER.readValue("{\"title\":\"t\"}", D7CustomDeserializerRequest.class))
            .as("creator 不在で落ちるのではなく、指定された deserializer 本体まで到達するはず")
            .isNotInstanceOf(InvalidDefinitionException.class);
        assertThat(JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
                fixtureClasses.get(D7CustomDeserializerRequest.class)))
            .as("using はクラス自身の生成手段なので番人は通すべき")
            .isFalse();
    }

    // ------------------------------------------------------------------
    // フォーム経路の裏取り（Jackson 注釈が効かないことの実測）
    // ------------------------------------------------------------------

    /**
     * {@code @Valid} だけが付いた引数の型も、Jackson から見れば同じく実体生成不能であることの実測。
     *
     * <p>フォームバインダ（{@code BeanUtils.getResolvableConstructor}）は Jackson とは別実装だが、
     * 「複数コンストラクタ＋引数無しコンストラクタ不在なら実体を作れない」という<b>破綻点は同じ</b>。
     * ここでは Jackson 側で実測しておく（フォーム側の判定は番人の
     * {@link JsonRequestBodyCreatorArchTest#lacksResolvableConstructor(JavaClass)} が担う）。
     */
    @Test
    @DisplayName("実測: @Valid のみで受ける壊れたDTOも実体生成不能（フォーム経路の検査対象に入るべき形）")
    void validOnlyBoundRequestIsAlsoUnconstructible() {
        assertThatThrownBy(() ->
            OBJECT_MAPPER.readValue("{\"keyword\":\"k\",\"page\":1}", D7ValidOnlyBoundRequest.class))
            .isInstanceOf(InvalidDefinitionException.class);
        assertThat(JsonRequestBodyCreatorArchTest.lacksResolvableConstructor(
                fixtureClasses.get(D7ValidOnlyBoundRequest.class)))
            .as("フォームバインダも同じ破綻点を持つため、番人はこの形を違反と判定すべき")
            .isTrue();
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    /** 実 ObjectMapper が壊れると判定し、かつ番人も違反と判定することを固定する。 */
    private static void assertBothRejected(Class<?> type, String json) {
        assertThatThrownBy(() -> OBJECT_MAPPER.readValue(json, type))
            .as("実 ObjectMapper はこの形のデシリアライザを構築できないはず"
                + "（Spring では HttpMessageConversionException になり常時 500）")
            .isInstanceOf(InvalidDefinitionException.class)
            .isInstanceOf(JsonMappingException.class);
        assertThat(JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
                fixtureClasses.get(type)))
            .as("実際に壊れる形を番人が通してはならない（偽陰性）")
            .isTrue();
    }

    /** 実 ObjectMapper が往復でき、かつ番人も合格と判定することを固定する。 */
    private static void assertBothAccepted(Class<?> type, String json) {
        assertThatCode(() -> {
            Object value = OBJECT_MAPPER.readValue(json, type);
            assertThat(value).isNotNull();
        })
            .as("実 ObjectMapper が往復できる形であるはず")
            .doesNotThrowAnyException();
        assertThat(JsonRequestBodyCreatorArchTest.lacksUsableJacksonCreator(
                fixtureClasses.get(type)))
            .as("実際に往復できる形を番人が弾いてはならない（偽陽性）")
            .isFalse();
    }
}
