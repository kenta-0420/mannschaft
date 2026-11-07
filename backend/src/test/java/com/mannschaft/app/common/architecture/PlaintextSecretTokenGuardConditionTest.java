package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.PlaintextSecretTokenGuardRules.GuardResult;
import com.mannschaft.app.common.architecture.plaintexttoken.CsvPreviewLikeFixtureService;
import com.mannschaft.app.common.architecture.plaintexttoken.ExemptedSecretFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.ExemptedSecretFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.FlowSelfIssuingFixtureService;
import com.mannschaft.app.common.architecture.plaintexttoken.HashedTokenFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.HashedTokenFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.IssuedTokenHoldingFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.IssuedTokenParamFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.JdbcSelfRandomFixtureSink;
import com.mannschaft.app.common.architecture.plaintexttoken.JtiSessionFixtureService;
import com.mannschaft.app.common.architecture.plaintexttoken.MisdeclaredHashExemptionFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.MisdeclaredExemptionFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.MisdeclaredExemptionFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.PlaintextSecretFixtureSuperclass;
import com.mannschaft.app.common.architecture.plaintexttoken.PlaintextSecretSubFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.PlaintextTokenFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.PlaintextTokenFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.SelfIssuingFixtureService;
import com.mannschaft.app.common.architecture.plaintexttoken.ValkeyBearerFixtureService;
import com.mannschaft.app.common.token.BearerSecretValkeyKey;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 試練（CMP-260826-1457 W0）: 番人 {@link PlaintextSecretTokenGuardArchTest} の自己検証（AC-20・21・22・27・28）。
 *
 * <p>fixture パッケージ {@code com.mannschaft.app.common.architecture.plaintexttoken} だけを取り込み、番人の判定の単一正準
 * {@link PlaintextSecretTokenGuardRules} を評価する。<b>陽性 fixture</b>（違反として検出されるべき形）が検出され、
 * <b>陰性 fixture</b>（検出されてはならない形）が検出されないことの両方を確かめる。</p>
 *
 * <p>red の理由: 試練の時点では {@link PlaintextSecretTokenGuardRules} がスタブで、常に空の結果を返す。
 * 陽性を検出せよと求めるテストは失敗する。陰性のテストはスタブでも通るが、実装後の偽陽性を止める非回帰条件として置く。</p>
 */
@DisplayName("試練 CMP-260826-1457: 平文トークン番人の自己検証（陽性・陰性 fixture）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag(ArchUnitTestTag.ARCHUNIT)
class PlaintextSecretTokenGuardConditionTest {

    private static final String FIXTURES_PACKAGE = "com.mannschaft.app.common.architecture.plaintexttoken";

    private JavaClasses fixtureClasses;

    @BeforeAll
    void importFixtures() {
        fixtureClasses = new ClassFileImporter().importPackages(FIXTURES_PACKAGE);
    }

    private static String field(Class<?> owner, String fieldName) {
        return owner.getName() + "." + fieldName;
    }

    private static String method(Class<?> owner, String methodName) {
        return owner.getName() + "#" + methodName;
    }

    /** 要素が識別子そのもの、または「識別子 + 半角空白 + 理由」であるかを判定する（{@link PlaintextSecretTokenGuardRules} の表記の約束）。 */
    private static boolean refersTo(String element, String identifier) {
        return element.equals(identifier) || element.startsWith(identifier + " ");
    }

    private static void assertListed(List<String> elements, String identifier, String why) {
        assertThat(elements)
                .as("%s が検出されるべき（%s）。実際: %s", identifier, why, elements)
                .anyMatch(element -> refersTo(element, identifier));
    }

    private static void assertNotListed(List<String> elements, String identifier, String why) {
        assertThat(elements)
                .as("%s は検出されてはならない（%s）。実際: %s", identifier, why, elements)
                .noneMatch(element -> refersTo(element, identifier));
    }

    @Nested
    @DisplayName("番人1（AC-20）: 平文の token 系列")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Columns {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.plaintextColumns(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: 名前が token の String 列は違反")
        void plainStringTokenColumnIsViolation() {
            assertListed(result.violations(), field(PlaintextTokenFixtureEntity.class, "token"),
                    "String の token 列で、_hash でなく免除注釈も無い");
        }

        @Test
        @DisplayName("陽性: 名前が passcode の char[] 列は違反")
        void charArrayPasscodeColumnIsViolation() {
            assertListed(result.violations(), field(PlaintextTokenFixtureEntity.class, "passcode"),
                    "char[] も文字列と同じく検査対象");
        }

        @Test
        @DisplayName("陽性: フィールド名は一致しなくても @Column(name = share_key) なら違反")
        void columnNameMatchIsViolation() {
            assertListed(result.violations(), field(PlaintextTokenFixtureEntity.class, "displayCode"),
                    "@Column(name) も語彙判定に使う");
        }

        @Test
        @DisplayName("陽性: @MappedSuperclass に宣言された secret 列も違反")
        void mappedSuperclassSecretIsViolation() {
            assertListed(result.violations(), field(PlaintextSecretFixtureSuperclass.class, "secret"),
                    "MappedSuperclass の宣言フィールドも検査する");
        }

        @Test
        @DisplayName("陽性: reason が空・空白だけの @PlaintextSecretRequired は免除にならない")
        void blankReasonIsNotExemption() {
            assertListed(result.violations(), field(MisdeclaredExemptionFixtureEntity.class, "webhookSecret"),
                    "reason が空文字");
            assertListed(result.violations(), field(MisdeclaredExemptionFixtureEntity.class, "apiSecret"),
                    "reason が空白だけ");
        }

        @Test
        @DisplayName("陽性（AC-27）: 他の戦役の cmp を名乗る @PlaintextTokenDebt は免除にならない")
        void foreignDebtCmpIsNotExemption() {
            assertListed(result.violations(), field(MisdeclaredExemptionFixtureEntity.class, "previewToken"),
                    "cmp が台帳に未登録または完了済み債務注釈");
        }

        @Test
        @DisplayName("陽性: ハッシュ列・語彙外の列に付いた無効な免除注釈（cmp 違い・reason 空）も違反になる")
        void invalidExemptionOnAnyColumnIsViolation() {
            assertListed(result.violations(), field(MisdeclaredHashExemptionFixtureEntity.class, "tokenHash"),
                    "ハッシュ列に cmp=OTHER-CMP");
            assertListed(result.violations(), field(MisdeclaredHashExemptionFixtureEntity.class, "resetTokenHash"),
                    "ハッシュ列に reason が空");
            assertListed(result.violations(), field(MisdeclaredHashExemptionFixtureEntity.class, "note"),
                    "語彙に一致しない列に cmp=OTHER-CMP");
        }

        @Test
        @DisplayName("陰性: 列名が _hash で終わる列は違反でない")
        void hashColumnIsNotViolation() {
            assertNotListed(result.violations(), field(HashedTokenFixtureEntity.class, "tokenHash"), "token_hash 列");
            assertListed(result.inspected(), field(HashedTokenFixtureEntity.class, "tokenHash"),
                    "token_hash も語彙に一致するので検査対象には拾う（拾ったうえで合格させる）");
        }

        @Test
        @DisplayName("陰性: 正しい免除注釈（reason あり／cmp=CMP-260826-1457）は違反でなく免除一覧に載る")
        void validExemptionsAreExempted() {
            String signingSecret = field(ExemptedSecretFixtureEntity.class, "signingSecret");
            String inviteToken = field(ExemptedSecretFixtureEntity.class, "inviteToken");
            assertNotListed(result.violations(), signingSecret, "@PlaintextSecretRequired(reason あり)");
            assertNotListed(result.violations(), inviteToken, "@PlaintextTokenDebt(cmp=CMP-260826-1457)");
            assertListed(result.exempted(), signingSecret, "恒久除外として免除一覧に載る");
            assertListed(result.exempted(), inviteToken, "移行期間の債務として免除一覧に載る");
        }

        @Test
        @DisplayName("陰性: 名前が一致しても型が String/char[] でない列（Long の ID・日時・UUID）は検査対象外")
        void nonStringTypesAreNotInspected() {
            for (String name : List.of("tokenId", "tokenExpiresAt", "leaseToken")) {
                String id = field(PlaintextTokenFixtureEntity.class, name);
                assertNotListed(result.violations(), id, "型で対象外");
                assertNotListed(result.inspected(), id, "型で対象外");
            }
        }

        @Test
        @DisplayName("陰性: 語彙に一致しない String 列（correlationId）は検査対象外")
        void unrelatedStringIsNotInspected() {
            assertNotListed(result.inspected(), field(HashedTokenFixtureEntity.class, "correlationId"), "語彙に一致しない");
        }
    }

    @Nested
    @DisplayName("番人2（AC-21）: 平文の token 系列を検索する Repository メソッド")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RepositoryLookups {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.plaintextRepositoryLookups(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: findByToken・existsByToken は違反")
        void derivedPlaintextLookupsAreViolations() {
            assertListed(result.violations(), method(PlaintextTokenFixtureRepository.class, "findByToken"),
                    "平文の token 列を derived query で検索");
            assertListed(result.violations(), method(PlaintextTokenFixtureRepository.class, "existsByToken"),
                    "existsBy も検索として扱う");
        }

        @Test
        @DisplayName("陽性: 名前が token 系でなくても @Query 本文が e.token = で引くなら違反")
        void queryBodyPlaintextLookupIsViolation() {
            assertListed(result.violations(), method(PlaintextTokenFixtureRepository.class, "lookupByValue"),
                    "@Query 本文の平文列の等値比較");
        }

        @Test
        @DisplayName("陽性: MappedSuperclass から継承した secret 列の検索も違反")
        void inheritedSecretLookupIsViolation() {
            assertListed(result.violations(), method(PlaintextSecretSubFixtureRepository.class, "findBySecret"),
                    "対応フィールドの解決は継承元まで辿る");
        }

        @Test
        @DisplayName("陽性（AC-27）: 対応フィールドの債務注釈が他の戦役の cmp なら免除にならない")
        void foreignDebtFieldLookupIsViolation() {
            assertListed(result.violations(),
                    method(MisdeclaredExemptionFixtureRepository.class, "findByPreviewToken"),
                    "無効な免除注釈");
        }

        @Test
        @DisplayName("陰性: ハッシュ列で引くメソッド（derived・@Query の両方）は違反でない")
        void hashLookupsAreNotViolations() {
            assertNotListed(result.violations(), method(HashedTokenFixtureRepository.class, "findByTokenHash"),
                    "Hash を含む");
            assertNotListed(result.violations(), method(HashedTokenFixtureRepository.class, "lookupByHash"),
                    "@Query 本文が tokenHash を比較");
        }

        @Test
        @DisplayName("陰性: 対応フィールドに正しい免除注釈がある検索は免除される")
        void exemptedFieldLookupsAreExempted() {
            String signing = method(ExemptedSecretFixtureRepository.class, "findBySigningSecret");
            String invite = method(ExemptedSecretFixtureRepository.class, "findByInviteToken");
            assertNotListed(result.violations(), signing, "@PlaintextSecretRequired の列");
            assertNotListed(result.violations(), invite, "@PlaintextTokenDebt の列");
            assertListed(result.exempted(), signing, "免除一覧に載る");
            assertListed(result.exempted(), invite, "免除一覧に載る");
        }
    }

    @Nested
    @DisplayName("番人3（AC-22）: 金庫を通さない自前発行")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SelfIssued {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.selfIssuedTokens(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: UUID.randomUUID() と Entity の token setter の同居は違反")
        void uuidWithSetterIsViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "issueWithUuidSetter"),
                    "UUID.randomUUID() + setToken");
        }

        @Test
        @DisplayName("陽性: UUID.randomUUID() と Entity の builder の token メソッドの同居は違反")
        void uuidWithBuilderIsViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "issueWithUuidBuilder"),
                    "UUID.randomUUID() + builder().token(...)（Lombok builder の形）");
        }

        @Test
        @DisplayName("陽性: SecureRandom#nextBytes と Entity の token setter の同居は違反")
        void secureRandomWithSetterIsViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "issueWithSecureRandom"),
                    "SecureRandom#nextBytes + setToken");
        }

        @Test
        @DisplayName("陽性（AC-27）: 他の戦役の cmp を名乗る債務注釈のメソッドは免除にならない")
        void foreignDebtMethodIsViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "issueUnderForeignDebt"),
                    "cmp が台帳に未登録または完了済み");
        }

        @Test
        @DisplayName("陽性（AC-22）: UUID.randomUUID() を vault.hash() に通して setTokenHash に詰める金庫外発行も違反")
        void uuidThenHashIsViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "issueWithUuidThenHash"),
                    "Hash を含む書込みでも、金庫を通さない乱数の発行は違反");
        }

        @Test
        @DisplayName("陽性（AC-27）: Entity に書かない乱数ヘルパでも、他の戦役の cmp を名乗る債務注釈は常に違反")
        void foreignDebtIsAlwaysViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "randomHelperUnderForeignDebt"),
                    "cmp の検査は違反の形に関係なく効く");
        }

        @Test
        @DisplayName("陰性: 正しい債務注釈のメソッドは違反でなく免除一覧に載る")
        void debtMethodIsExempted() {
            String id = method(SelfIssuingFixtureService.class, "issueUnderDebt");
            assertNotListed(result.violations(), id, "@PlaintextTokenDebt(cmp=CMP-260826-1457)");
            assertListed(result.exempted(), id, "免除一覧に載る");
        }

        @Test
        @DisplayName("陰性: 金庫で発行してハッシュだけを詰めるメソッドは違反でない")
        void vaultIssuingIsNotViolation() {
            assertNotListed(result.violations(), method(SelfIssuingFixtureService.class, "issueViaVault"),
                    "自前乱数を呼ばない");
        }

        @Test
        @DisplayName("陰性: JTI 用の UUID と、別経路の乱数トークン（外から受領・別クラスのヘルパ）が同居しても違反でない")
        void jtiUuidWithSeparateTokenIsNotViolation() {
            assertNotListed(result.violations(), method(JtiSessionFixtureService.class, "issueWithJtiAndExternalToken"),
                    "UUID は JTI にだけ届く");
            assertNotListed(result.violations(), method(JtiSessionFixtureService.class, "issueWithJtiAndHelperToken"),
                    "UUID は JTI にだけ届く。トークンは別クラスのヘルパ由来");
            assertListed(result.inspected(), method(JtiSessionFixtureService.class, "issueWithJtiAndHelperToken"),
                    "自前乱数を呼ぶメソッドとして検査対象には拾う");
        }

        @Test
        @DisplayName("陽性: UUID が token_hash に届く、または同じメソッドの SecureRandom の値が token 系へ届く形は違反")
        void uuidOrLocalRandomReachingTokenIsViolation() {
            assertListed(result.violations(), method(JtiSessionFixtureService.class, "uuidReachesTokenHash"),
                    "UUID → hash → setTokenHash");
            assertListed(result.violations(), method(JtiSessionFixtureService.class, "jtiAndLocalSecureRandomToken"),
                    "JTI の UUID があっても SecureRandom → hex → hash → setTokenHash は届いている");
        }

        @Test
        @DisplayName("陰性: UUID を token 系でない列に詰める・token 系の名前でも日時を受け取る setter は違反でない")
        void unrelatedUuidUsesAreNotViolations() {
            assertNotListed(result.violations(), method(SelfIssuingFixtureService.class, "stampCorrelation"),
                    "setCorrelationId は token 系でない");
            assertNotListed(result.violations(), method(SelfIssuingFixtureService.class, "stampExpiry"),
                    "setTokenExpiresAt は String/char[] を受け取らない");
            assertListed(result.inspected(), method(SelfIssuingFixtureService.class, "stampCorrelation"),
                    "自前乱数を呼ぶメソッドとして検査対象には拾う");
        }
    }

    @Nested
    @DisplayName("番人3 の値の流れ（メソッド内の分岐合流・ループ・例外辺・配列の別名）と既知の偽陰性")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SelfIssuedFlow {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.selfIssuedTokens(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: if/else の一方で乱数、他方で固定値を代入して合流しても違反")
        void branchMergeIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithBranchMerge"),
                    "else 枝のきれいな代入で乱数由来の印が消えてはならない");
            assertListed(result.violations(),
                    method(FlowSelfIssuingFixtureService.class, "issueWithBranchMergeOverwrite"),
                    "分岐の中で固定値に上書きする枝があっても、もう一方は乱数");
        }

        @Test
        @DisplayName("陽性: 三項演算子で乱数と固定値が合流しても違反")
        void ternaryMergeIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithTernary"),
                    "合流したスタックの印の和集合をとる");
        }

        @Test
        @DisplayName("陽性: 乱数を詰める配列の別名から取り出した値も違反")
        void arrayAliasIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithArrayAlias"),
                    "nextBytes(bytes) のあと alias から取り出す");
        }

        @Test
        @DisplayName("陽性: 別名のあと、分岐の中・三項演算子で別名の変数へ代入しても関係は保たれ違反")
        void aliasKeptAcrossBranchIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithAliasKeptInBranch"),
                    "分岐の中の代入で別名を解除してはならない（may-alias）");
            assertListed(result.violations(),
                    method(FlowSelfIssuingFixtureService.class, "issueWithAliasKeptByTernary"),
                    "三項演算子の合流でも別名を保つ");
        }

        @Test
        @DisplayName("陽性: ループの後方ジャンプで次の周回へ持ち越される乱数・配列の別名も違反")
        void loopCarriedFlowIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithLoopCarriedRandom"),
                    "1周目の終わりに詰めた乱数が2周目の先頭で token 系に届く（周回後の状態を再評価する）");
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithLoopCarriedAlias"),
                    "1周目の終わりに作った別名が2周目へ持ち越される");
        }

        @Test
        @DisplayName("陽性: try の途中の例外で catch・finally・外側の catch へ移る経路の乱数も違反（例外辺）")
        void exceptionEdgeFlowIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithTaintInCatch"),
                    "try 範囲の各命令の実行前の状態がハンドラへ合流する");
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithTaintInFinally"),
                    "finally（例外経路の複製）も同じ規則で合流する");
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithTaintInNestedTry"),
                    "入れ子の try でも、外側のハンドラへ内側の try 範囲の状態が合流する");
        }

        @Test
        @DisplayName("陽性: カテゴリ2の値（long・double）を配列要素へ代入する式の値も違反（DUP2_X2）")
        void categoryTwoArrayAssignExpressionIsViolation() {
            assertListed(result.violations(),
                    method(FlowSelfIssuingFixtureService.class, "issueWithLongArrayAssignInExpression"),
                    "long の代入式の値は DUP2_X2 でスタックに残り token 系に届く");
            assertListed(result.violations(),
                    method(FlowSelfIssuingFixtureService.class, "issueWithDoubleArrayAssignInExpression"),
                    "double の代入式の値も同じ");
        }

        @Test
        @DisplayName("陽性: 2次元配列の内側の要素を経由した乱数も違反（内側の配列参照は外側の作った場所を受け継ぐ）")
        void multiDimensionalArrayFlowIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithTwoDimensionalArray"),
                    "box[0][0] への書込みで外側の配列に印が付き、読出しで印が伝わる");
        }

        @Test
        @DisplayName("陽性: 配列の別名（外側への格納・取り出し・コピー・分岐合流・3次元）を経由した乱数も違反（別名の同値類に印を付ける）")
        void arrayAliasClassFlowIsViolation() {
            for (String name : List.of("issueWithRowStoredThenTainted", "issueWithRowTaintedThenStored",
                    "issueWithThreeDimensionalAlias", "issueWithCopiedAliasWrite", "issueWithBranchMergedAlias")) {
                assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, name),
                        "格納した参照と格納先は同じ別名の類になり、どの別名から書いても読んでも印が伝わる");
            }
        }

        @Test
        @DisplayName("陽性: 乱数の文字列を split した配列の要素も違反（値自体の印は要素の読出しに伝わる）")
        void splitOfRandomIsViolation() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueWithSplitOfRandom"),
                    "AALOAD の結果は配列の値自体の印と類の印を合わせる");
        }

        /**
         * 既知の偽陽性（2026-11-05 殿裁定）。配列の別名を union-find の同値類で保守的に近似するため、共通の格納先を通じて
         * 乱数を受けていない配列も同じ類になり検出される。番人は安全側に倒す方針で許容する。
         * 誤検出したときは配列を分けるか {@code @PlaintextTokenDebt} を付ける。近似を精密にしたら期待値を反転する。
         */
        @Test
        @DisplayName("既知の偽陽性: 乱数の配列と同じ外側の配列へ格納したきれいな配列も検出される（保守的近似。精密化したら反転）")
        void sharedBoxOverMergeIsKnownFalsePositive() {
            assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "cleanRowSharingBoxWithRandomRow"),
                    "既知の偽陽性: 共通の格納先を通じて類が併合される");
        }

        @Test
        @DisplayName("陽性: 戻り値・引数・フィールドから読んだローカル・CHECKCAST（同じ参照の2回のキャストを含む）の配列に書いて読む乱数も違反（参照値はすべて作った場所を持ち、キャストは同一性を保つ）")
        void arrayFromAnySourceIsViolation() {
            for (String name : List.of("issueWithReturnedArrayWrite", "issueWithArgumentArrayWrite",
                    "issueWithFieldArrayReadIntoLocal", "issueWithCastArrayWrite", "issueWithTwiceCastObject")) {
                assertListed(result.violations(), method(FlowSelfIssuingFixtureService.class, name),
                        "配列参照を生む命令はすべて命令位置の合成 site を持ち、要素への書込みの印が保持される");
            }
        }

        @Test
        @DisplayName("陰性: 別の配列に乱数を入れても、token 系へ渡す配列には伝わらない")
        void separateArraysAreNotViolation() {
            String id = method(FlowSelfIssuingFixtureService.class, "separateArraysDoNotShareTaint");
            assertNotListed(result.violations(), id, "別名でない配列どうしは別の類");
            assertListed(result.inspected(), id, "自前乱数を呼ぶメソッドとして検査対象には拾う");
        }

        @Test
        @DisplayName("陰性: UUID が相関 ID にだけ届き、token 系は固定値同士の三項演算子なら違反でない")
        void cleanTernaryIsNotViolation() {
            String id = method(FlowSelfIssuingFixtureService.class, "ternaryCleanTokenWithCorrelationUuid");
            assertNotListed(result.violations(), id, "token 系へ届く値は乱数由来でない");
            assertListed(result.inspected(), id, "自前乱数を呼ぶメソッドとして検査対象には拾う");
        }

        /**
         * 既知の偽陰性（2026-11-03・11-04 殿裁定）。番人3が追うのはメソッド内のローカル変数の流れ（通常・後方・例外の辺）だけで、
         * メソッドをまたぐ流れ・フィールド経由・ラムダの本体・リフレクションは見逃す。
         * 将来これらを検出できるようになったら、{@code assertNotListed} を {@code assertListed} に反転すること。
         */
        @Test
        @DisplayName("既知の偽陰性: ヘルパ・フィールド・ラムダの本体・リフレクションを経由した乱数の流れは検出されない（検出できるようになったら反転）")
        void outOfScopeFlowsAreKnownFalseNegatives() {
            assertNotListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueViaGeneratorHelper"),
                    "既知の限界（メソッドをまたぐ流れ）: 乱数の生成ヘルパの戻り値");
            assertNotListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueViaWriterHelper"),
                    "既知の限界（メソッドをまたぐ流れ）: 乱数を書込みヘルパへ渡す");
            assertNotListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueViaField"),
                    "既知の限界: フィールド経由");
            assertNotListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueViaLambdaBody"),
                    "既知の限界: ラムダの本体");
            assertNotListed(result.violations(), method(FlowSelfIssuingFixtureService.class, "issueViaReflection"),
                    "既知の限界: リフレクション");
        }
    }

    @Nested
    @DisplayName("債務注釈の cmp 検査: 台帳に登録された未完了の CMP ID だけを許す")
    class DebtCmpValidation {

        private static final String LEDGER = String.join("\n",
                "| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |",
                "| CMP-260101-0001 | 実装中の戦役 | 実装中 | — | 条件 | — | — |",
                "| CMP-260101-0002 | 完了した戦役 | 完了（2026-10-21） | — | 条件 | — | — |",
                "| CMP-260101-0003 | 半角括弧の完了 | 完了(2026-10-10) | — | 条件 | — | — |",
                "| CMP-260101-0004 | 戦役名に \\| を含む | 未着手 | — | 条件 | — | — |",
                "| CMP-005 | 旧連番形式 | 対応中 | — | 条件 | — | — |");

        @Test
        @DisplayName("許可: 登録済みで未完了の ID（日時形式・旧連番形式・戦役名に | を含む行）")
        void openRegisteredIdsAreAccepted() {
            for (String id : List.of("CMP-260101-0001", "CMP-260101-0004", "CMP-005")) {
                assertThat(PlaintextSecretTokenGuardRules.isOpenRegisteredCmp(id, LEDGER))
                        .as("%s は登録済みで未完了", id).isTrue();
            }
        }

        @Test
        @DisplayName("拒否: 完了済み・未登録・任意の文字列")
        void completedUnregisteredAndArbitraryIdsAreRejected() {
            for (String id : List.of("CMP-260101-0002", "CMP-260101-0003", "CMP-999999-0000", "OTHER-CMP", "",
                    "CMP-2601")) {
                assertThat(PlaintextSecretTokenGuardRules.isOpenRegisteredCmp(id, LEDGER))
                        .as("%s は拒否", id).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("番人4（AC-22）: IssuedToken の永続化")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class IssuedTokenPersistence {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.issuedTokenPersistence(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: IssuedToken を Entity のフィールド型に使うと違反（@Transient でも）")
        void issuedTokenFieldIsViolation() {
            assertListed(result.violations(), field(IssuedTokenHoldingFixtureEntity.class, "issued"),
                    "平文とハッシュの対を Entity に抱えている");
        }

        @Test
        @DisplayName("陽性: IssuedToken を Repository メソッドの引数に使うと違反")
        void issuedTokenRepositoryParamIsViolation() {
            assertListed(result.violations(), method(IssuedTokenParamFixtureRepository.class, "countByIssued"),
                    "平文ごと永続化層へ渡している");
        }

        @Test
        @DisplayName("陰性: IssuedToken を使わない Repository メソッドは違反でない")
        void ordinaryRepositoryMethodIsNotViolation() {
            assertNotListed(result.violations(), method(IssuedTokenParamFixtureRepository.class, "findAllByIdIn"),
                    "引数が Long の集合");
        }

        @Test
        @DisplayName("陰性: 金庫の発行結果をローカル変数で扱うだけのサービスは違反でない")
        void serviceLocalUseIsNotViolation() {
            assertNotListed(result.violations(), method(SelfIssuingFixtureService.class, "issueViaVault"),
                    "Entity・Repository ではない");
        }
    }

    @Nested
    @DisplayName("番人6（AC-28）: 列を通らない書き込みと自前乱数の同居")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RawWritesAndBearerKeys {

        private GuardResult result;

        @BeforeAll
        void evaluate() {
            result = PlaintextSecretTokenGuardRules.rawWritesAndBearerKeysWithSelfRandom(fixtureClasses);
        }

        @Test
        @DisplayName("陽性: JDBC の INSERT INTO と UUID.randomUUID() の同居は違反（ConfirmableFanoutChunkSink 型）")
        void jdbcInsertWithUuidIsViolation() {
            assertListed(result.violations(), method(JdbcSelfRandomFixtureSink.class, "insertWithUuid"),
                    "StringBuilder で組む INSERT INTO + 行ごとの UUID.randomUUID()");
        }

        @Test
        @DisplayName("陽性: JDBC の UPDATE と SecureRandom#nextBytes の同居は違反")
        void jdbcUpdateWithSecureRandomIsViolation() {
            assertListed(result.violations(), method(JdbcSelfRandomFixtureSink.class, "updateWithSecureRandom"),
                    "UPDATE + SecureRandom#nextBytes");
        }

        @Test
        @DisplayName("陽性: 定数接頭辞（インライン化される）で BearerSecretValkeyKey.of と UUID.randomUUID() を同居させると違反")
        void bearerKeyWithUuidIsViolation() {
            assertListed(result.violations(), method(ValkeyBearerFixtureService.class, "issueWithUuid"),
                    "of(KEY_PREFIX, UUID.randomUUID().toString())");
        }

        @Test
        @DisplayName("陽性: BearerSecretValkeyKey.of と SecureRandom#nextBytes の同居は違反")
        void bearerKeyWithSecureRandomIsViolation() {
            assertListed(result.violations(), method(ValkeyBearerFixtureService.class, "issueWithSecureRandom"),
                    "of(KEY_PREFIX, 自前乱数の hex)");
        }

        @Test
        @DisplayName("陽性（AC-27）: 番人6でも、他の戦役の cmp を名乗る債務注釈は形に関係なく違反")
        void foreignDebtIsAlwaysViolation() {
            assertListed(result.violations(), method(SelfIssuingFixtureService.class, "randomHelperUnderForeignDebt"),
                    "cmp の検査は常に効く");
        }

        @Test
        @DisplayName("陰性: 正しい債務注釈のメソッドは違反でなく免除一覧に載る（JDBC・Valkey の両方）")
        void debtMethodsAreExempted() {
            String jdbc = method(JdbcSelfRandomFixtureSink.class, "insertUnderDebt");
            String valkey = method(ValkeyBearerFixtureService.class, "issueUnderDebt");
            assertNotListed(result.violations(), jdbc, "@PlaintextTokenDebt");
            assertNotListed(result.violations(), valkey, "@PlaintextTokenDebt");
            assertListed(result.exempted(), jdbc, "免除一覧に載る");
            assertListed(result.exempted(), valkey, "免除一覧に載る");
        }

        @Test
        @DisplayName("陰性: 金庫のハッシュを使う INSERT・of 呼び出し、SELECT と相関 ID は違反でない")
        void vaultBasedWritesAreNotViolations() {
            assertNotListed(result.violations(), method(JdbcSelfRandomFixtureSink.class, "insertWithVault"), "金庫のハッシュ");
            assertNotListed(result.violations(), method(JdbcSelfRandomFixtureSink.class, "selectWithCorrelation"),
                    "SELECT は書き込みでない");
            assertNotListed(result.violations(), method(ValkeyBearerFixtureService.class, "issueViaVault"), "金庫のハッシュ");
            assertNotListed(result.violations(), method(ValkeyBearerFixtureService.class, "resolve"),
                    "受け取った平文を金庫でハッシュして引く");
        }

        @Test
        @DisplayName("陰性対照: CSV 一時キーの形（定数接頭辞＋UUID＋opsForValue().set、of を呼ばない）は違反にも免除にも数えない")
        void csvTemporaryKeyIsOutOfScope() {
            String id = method(CsvPreviewLikeFixtureService.class, "preview");
            assertNotListed(result.violations(), id, "BearerSecretValkeyKey.of を呼ばない一時キー");
            assertNotListed(result.exempted(), id, "対象外は免除一覧にも載らない");
        }

        @Test
        @DisplayName("前提の確認: 陽性 fixture の接頭辞はインライン化され、バイトコードに残るのは of の呼び出しだけ")
        void fixturePrefixIsInlinedAndOnlyOfCallRemains() {
            JavaMethod issueWithUuid = fixtureClasses.get(ValkeyBearerFixtureService.class)
                    .getMethod("issueWithUuid", org.springframework.data.redis.core.StringRedisTemplate.class);
            assertThat(issueWithUuid.getFieldAccesses())
                    .as("KEY_PREFIX はコンパイル時定数としてインライン化され、フィールド参照が残らない（実物と同じ条件）")
                    .noneMatch(access -> access.getTarget().getName().equals("KEY_PREFIX"));
            assertThat(issueWithUuid.getMethodCallsFromSelf())
                    .as("BearerSecretValkeyKey.of の INVOKESTATIC は残る")
                    .anyMatch(call -> call.getTargetOwner().isEquivalentTo(BearerSecretValkeyKey.class)
                            && call.getName().equals("of"));
        }
    }
}
