package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.architecture.PlaintextSecretTokenGuardRules.GuardResult;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 番人（CMP-260826-1457 §2-4 番人1〜6）: 招待・共有トークンなど「知っている者だけが通れる合言葉」を平文で保存させない。
 *
 * <p>判定ロジックの単一正準は {@link PlaintextSecretTokenGuardRules}。判定の正しさ（陽性を検出し陰性を誤検出しないこと）は
 * {@link PlaintextSecretTokenGuardConditionTest} が fixture で自己検証する。本クラスは本番クラス全体
 * （{@link ProductionClasses#get()}、テストクラスを除く）に番人を掛ける。</p>
 *
 * <h2>W0 で緑に立てる条件（AC-27）</h2>
 * <ul>
 *   <li>既存の違反（台帳の16系統の平文列・金庫を通さない発行メソッド）は、すべて {@code @PlaintextTokenDebt(cmp = "CMP-260826-1457")}
 *       で列挙して免除する。1-C の恒久除外は {@code @PlaintextSecretRequired(reason)}。</li>
 *   <li>W1〜W2 の各波は自分の系統の債務注釈を<b>外すだけ</b>で、本クラスを書き換えない。債務注釈の型は汎用の仕組みとして
 *       残し（削除しない）、W3 は {@code cmp="CMP-260826-1457"} の債務がゼロであることを確認する。他戦役の債務は
 *       台帳に登録された未完了の CMP ID のときだけ許す。</li>
 * </ul>
 *
 * <h2>試練（W0 red）での失敗の仕方</h2>
 * <p>{@link PlaintextSecretTokenGuardRules} がスタブ（空の結果）のため、「検査対象を1件以上拾う」非空性の検査と、
 * 台帳記載の平文列が免除一覧に載っていることの検査が失敗する。判定を実装しても債務注釈を付けなければ、違反ゼロの検査が失敗する。</p>
 */
@DisplayName("番人 CMP-260826-1457: 合言葉を平文で保存しない（番人1〜6・本番クラス全体）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag(ArchUnitTestTag.ARCHUNIT)
class PlaintextSecretTokenGuardArchTest {

    private static final String PRODUCTION_ROOT = "com.mannschaft.app.";

    private JavaClasses production;

    @BeforeAll
    void importProduction() {
        production = ProductionClasses.get();
    }

    private static boolean refersTo(String element, String identifier) {
        return element.equals(identifier) || element.startsWith(identifier + " ");
    }

    private static void assertNoViolationsAndNotVacuous(String guardName, GuardResult result) {
        assertThat(result.violations())
                .as("%s の違反（免除するなら @PlaintextTokenDebt(cmp=\"CMP-260826-1457\") は既存違反の列挙にだけ使い、"
                        + "新しく増やさない。平文が本質的に必要なら @PlaintextSecretRequired(reason)）: %s",
                        guardName, result.violations())
                .isEmpty();
        assertThat(result.inspected())
                .as("%s が本番クラスから検査対象を1件も拾っていない（判定ロジックの偽陰性の恐れ）", guardName)
                .isNotEmpty();
    }

    @Test
    @DisplayName("番人1: 本番の Entity に平文の token 系列が無い（既存は債務注釈で列挙済み）")
    void 番人1_平文のtoken系列が無い() {
        assertNoViolationsAndNotVacuous("番人1（列）", PlaintextSecretTokenGuardRules.plaintextColumns(production));
    }

    @Test
    @DisplayName("番人2: 本番の Repository に平文の token 系列を検索するメソッドが無い")
    void 番人2_平文検索メソッドが無い() {
        assertNoViolationsAndNotVacuous("番人2（Repository）",
                PlaintextSecretTokenGuardRules.plaintextRepositoryLookups(production));
    }

    @Test
    @DisplayName("番人3: 本番に金庫を通さない自前発行が無い")
    void 番人3_自前発行が無い() {
        assertNoViolationsAndNotVacuous("番人3（自前発行）", PlaintextSecretTokenGuardRules.selfIssuedTokens(production));
    }

    @Test
    @DisplayName("番人4: 本番で IssuedToken を Entity のフィールドや Repository の引数に使っていない")
    void 番人4_IssuedTokenを永続化しない() {
        assertNoViolationsAndNotVacuous("番人4（IssuedToken）",
                PlaintextSecretTokenGuardRules.issuedTokenPersistence(production));
    }

    @Test
    @DisplayName("番人6: 本番に JDBC 直接書き込み・BearerSecretValkeyKey.of と自前乱数の同居が無い")
    void 番人6_列を通らない書き込みに自前乱数が無い() {
        assertNoViolationsAndNotVacuous("番人6（列を通らない書き込み）",
                PlaintextSecretTokenGuardRules.rawWritesAndBearerKeysWithSelfRandom(production));
    }

    @Test
    @DisplayName("番人6 陰性対照（AC-28）: 本番の CSV 一時キー（BudgetCsvService・RepairPlanItemCsvService）は違反にも免除にも数えない")
    void 番人6_CSV一時キーは対象外() {
        GuardResult result = PlaintextSecretTokenGuardRules.rawWritesAndBearerKeysWithSelfRandom(production);
        List<String> csvClasses = List.of(
                "com.mannschaft.app.budget.service.BudgetCsvService",
                "com.mannschaft.app.repairplan.service.RepairPlanItemCsvService");
        for (String csvClass : csvClasses) {
            assertThat(production.contain(csvClass))
                    .as("陰性対照のクラス %s が本番に存在すること（改名されたら対照を付け替える）", csvClass)
                    .isTrue();
            assertThat(Stream.concat(result.violations().stream(), result.exempted().stream()))
                    .as("%s は合言葉キーではない（BearerSecretValkeyKey.of を呼ばない）ので、違反にも免除にも数えない", csvClass)
                    .noneMatch(element -> element.startsWith(csvClass + "#"));
        }
    }

    /**
     * 台帳（陣立て書 1-A・1-B）の DB 列系統。{@code (Entity の完全修飾名, 平文フィールド名)}。
     * 系統7（pointcard）・15（OAuth state）は Valkey なので列を持たない。
     */
    static Stream<Arguments> 台帳の平文列() {
        return Stream.of(
                Arguments.of("系統1 チーム/組織招待", "com.mannschaft.app.role.entity.InviteTokenEntity", "token"),
                Arguments.of("系統2 連絡先招待", "com.mannschaft.app.contact.entity.ContactInviteTokenEntity", "token"),
                Arguments.of("系統3 イベントゲスト招待", "com.mannschaft.app.event.entity.EventGuestInviteTokenEntity", "token"),
                Arguments.of("系統4 ファイル共有リンク", "com.mannschaft.app.filesharing.entity.SharedFileLinkEntity", "token"),
                Arguments.of("系統5 委員会招集", "com.mannschaft.app.committee.entity.CommitteeInvitationEntity", "inviteToken"),
                Arguments.of("系統6 サイネージ", "com.mannschaft.app.signage.entity.SignageAccessTokenEntity", "token"),
                Arguments.of("系統8 見守り招待", "com.mannschaft.app.family.entity.UserCareLinkEntity", "invitationToken"),
                Arguments.of("系統9 ブログ下書きプレビュー", "com.mannschaft.app.cms.entity.BlogPostEntity", "previewToken"),
                Arguments.of("系統10 チームページ プレビュー", "com.mannschaft.app.member.entity.TeamPageEntity", "previewToken"),
                Arguments.of("系統11 iCal 購読", "com.mannschaft.app.schedule.entity.UserIcalTokenEntity", "token"),
                Arguments.of("系統12 Incoming Webhook", "com.mannschaft.app.webhook.entity.IncomingWebhookTokenEntity", "token"),
                Arguments.of("系統13 確認付き通知",
                        "com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity",
                        "confirmToken"),
                Arguments.of("系統14 異議申立て", "com.mannschaft.app.moderation.entity.ModerationAppealEntity", "appealToken"),
                Arguments.of("系統16 Google Calendar channel token",
                        "com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity", "channelToken"));
    }

    @ParameterizedTest(name = "{0}: {1}.{2}")
    @MethodSource("台帳の平文列")
    @DisplayName("番人1の網羅（AC-27）: 台帳の平文列が残っている間は、番人1が検査対象に拾い、債務注釈で免除している")
    void 台帳の平文列は番人1が拾い債務として免除している(String system, String entityClassName, String fieldName)
            throws ClassNotFoundException {
        Class<?> entityClass = Class.forName(entityClassName);
        if (!declaresField(entityClass, fieldName)) {
            // 各波で平文フィールドが *Hash へ置き換わった後は、この行は検査不要（番人1の違反ゼロが残りを保証する）。
            return;
        }
        String identifier = entityClassName + "." + fieldName;
        GuardResult result = PlaintextSecretTokenGuardRules.plaintextColumns(production);
        assertThat(result.inspected())
                .as("%s の平文列 %s を番人1の語彙が拾えていない（語彙・型判定の偽陰性）", system, identifier)
                .anyMatch(element -> refersTo(element, identifier));
        assertThat(result.exempted())
                .as("%s の平文列 %s は、移行が済むまで @PlaintextTokenDebt(cmp=\"CMP-260826-1457\") で免除されているはず",
                        system, identifier)
                .anyMatch(element -> refersTo(element, identifier));
    }

    private static boolean declaresField(Class<?> type, String fieldName) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.add(c);
        }
        for (Class<?> c : hierarchy) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("前提: 本番クラスの取り込みが com.mannschaft.app 配下を含む")
    void 前提_本番クラスを取り込めている() {
        assertThat(production.stream().anyMatch(c -> c.getName().startsWith(PRODUCTION_ROOT))).isTrue();
    }
}
