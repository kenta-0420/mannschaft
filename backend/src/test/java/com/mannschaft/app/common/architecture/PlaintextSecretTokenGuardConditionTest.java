package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.PlaintextSecretTokenGuardRules.GuardResult;
import com.mannschaft.app.common.architecture.plaintexttoken.CsvPreviewLikeFixtureService;
import com.mannschaft.app.common.architecture.plaintexttoken.ExemptedSecretFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.ExemptedSecretFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.HashedTokenFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.HashedTokenFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.IssuedTokenHoldingFixtureEntity;
import com.mannschaft.app.common.architecture.plaintexttoken.IssuedTokenParamFixtureRepository;
import com.mannschaft.app.common.architecture.plaintexttoken.JdbcSelfRandomFixtureSink;
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
                    "cmp が CMP-260826-1457 でない債務注釈");
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
                    "cmp が CMP-260826-1457 でない");
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
