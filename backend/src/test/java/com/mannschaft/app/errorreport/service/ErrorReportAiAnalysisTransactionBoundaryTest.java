package com.mannschaft.app.errorreport.service;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2990 L4 再検分是正 — AI 分析のトランザクション境界を宣言レベルで固定する番人。
 *
 * <h2>何を守っているのか</h2>
 * <p>{@link ErrorReportAiAnalysisService#analyzeSync} は「①読み取り → ②AI 呼び出し（<b>TX外</b>）
 * → ③書き込み」の3段に分けてある。②を TX の中に戻すと、次の3つが同時に復活する。</p>
 * <ol>
 *   <li><b>接続保持</b> — AI 応答を待つ秒〜分のあいだ Hikari 接続を握り続ける。管理者の再分析 API は
 *       HTTP スレッドから直接この経路を叩くため {@code ai-analysis-pool} の max2 では縛れない</li>
 *   <li><b>接続枯渇</b> — 失敗時に {@code REQUIRES_NEW} の失敗記録が追加接続を要求し、
 *       接続取得タイムアウトで FAILED 記録自体が失敗する（＝再試行ループ防止が破れる）</li>
 *   <li><b>自己デッドロック</b> — 外側TXが {@code error_reports} の行ロックを保持したまま、
 *       同じ行を更新する {@code REQUIRES_NEW} を待つ</li>
 * </ol>
 *
 * <h2>3分割の保証は「呼び出し元」で失効する</h2>
 * <p>重要なのは、この保証が {@code analyzeSync} 自身の宣言だけでは<b>閉じない</b>ことである。
 * {@code analyzeSync} に {@code @Transactional} が無くても、<b>呼び出し元のどこか一箇所</b>に
 * {@code @Transactional} が付いた瞬間、Spring の伝播（既定 {@code REQUIRED}）によって
 * ②の AI 呼び出しは外側TXの中で走る。つまり上の3つの欠陥は、{@code analyzeSync} を一行も
 * 変えないまま、呼び出し元の一語の追加だけで静かに全部戻る。
 * 「バッチメソッドに {@code @Transactional} を足す」は極めて自然な変更であり、
 * 実際にそれが起きたときに気づける仕組みが無ければ、この PR の是正は無言で失効する。</p>
 *
 * <h2>将来の呼び出し元に自動追随する（手動更新は不要）</h2>
 * <p>そのため本番用バイトコードを ArchUnit で読み、{@code analyzeSync} を呼ぶ
 * <b>すべての</b>コードユニットを実行時に列挙して検査する。呼び出し元が増えても
 * 本テストの書き換えは要らず、<b>新しい呼び出し元は自動的に検査対象になる</b>。
 * 呼び出し元を固定リストで書き下すと、増えた経路が射程外のまま番人だけが緑になる
 * ——本 PR が {@code RejectionFallbackDeclared} で実証した「番人の穴」そのもの——ので、
 * 意図してリスト化していない。</p>
 *
 * <p>ただし限界はある: リフレクション経由や Spring のプロキシ／SpEL 経由の間接呼び出しは
 * バイトコード上に呼び出し辺が現れないため列挙できない。その種の経路を新設する場合は、
 * 本テストが自動では守らないことを承知したうえで明示的な検体を足すこと。</p>
 *
 * <p>これらは単体テストのモックでは現れない（モックが TX の実体を消す）ため、宣言そのものを検体にする。</p>
 */
@DisplayName("Issue #2990 L4: AI 分析のトランザクション境界")
class ErrorReportAiAnalysisTransactionBoundaryTest {

    /** 本番用バイトコード（テストクラスは除外）。 */
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.mannschaft.app");
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + "#" + name + " が見当たらない"));
    }

    @Test
    @DisplayName("analyzeSync は @Transactional を持たない（AI 呼び出しを TX の中に入れない）")
    void analyzeSyncは非トランザクション() {
        assertThat(method(ErrorReportAiAnalysisService.class, "analyzeSync")
                .getAnnotation(Transactional.class))
                .as("analyzeSync に @Transactional を付けると Claude API 呼び出しが TX の中に入り、"
                        + "接続保持・接続枯渇・自己デッドロックが同時に復活する")
                .isNull();
        assertThat(ErrorReportAiAnalysisService.class.getAnnotation(Transactional.class))
                .as("クラスレベルの @Transactional も付けないこと")
                .isNull();
    }

    @Test
    @DisplayName("analyzeSync を呼ぶ本番コードは1つ残らず TX を張らない（呼び出し元は自動列挙）")
    void 全呼び出し元が非トランザクション() {
        JavaMethod analyzeSync = productionClasses
                .get(ErrorReportAiAnalysisService.class)
                .getMethod("analyzeSync", Long.class, Long.class);

        List<JavaCodeUnit> callers = analyzeSync.getAccessesToSelf().stream()
                .map(access -> access.getOrigin())
                .filter(origin -> !origin.getOwner().isEquivalentTo(ErrorReportAiAnalysisService.class))
                .distinct()
                .toList();

        assertThat(callers)
                .as("analyzeSync の呼び出し元が1つも見つからないのは、バイトコード走査が"
                        + "失敗している（＝番人が何も検査していない）ことを意味する")
                .isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (JavaCodeUnit caller : callers) {
            JavaClass owner = caller.getOwner();
            if (owner.isAnnotatedWith(Transactional.class)) {
                violations.add(owner.getName() + " にクラスレベルの @Transactional が付いている");
            }
            if (caller.isAnnotatedWith(Transactional.class)) {
                violations.add(owner.getName() + "#" + caller.getName() + " に @Transactional が付いている");
            }
        }

        assertThat(violations)
                .as("analyzeSync を呼ぶ経路のどこかに @Transactional が付くと、AI 呼び出し（②）が"
                        + "外側TXの中に入り、接続保持・接続枯渇・自己デッドロックが復活する。"
                        + "TX が要るなら analyzeSync を呼ぶ前後に切り出すか、"
                        + "呼び出し部だけ TX の外へ出すこと。検出された呼び出し元: "
                        + callers.stream().map(c -> c.getOwner().getSimpleName() + "#" + c.getName()).toList())
                .isEmpty();
    }

    @Test
    @DisplayName("書き込みは短命TXの別 Bean（成功=@Transactional / 失敗=REQUIRES_NEW）")
    void 書き込みは別Beanの短命TX() {
        Transactional success = method(ErrorReportAiAnalysisResultRecorder.class, "recordSuccess")
                .getAnnotation(Transactional.class);
        assertThat(success).as("recordSuccess に @Transactional があること").isNotNull();

        Transactional failure = method(ErrorReportAiAnalysisFailureRecorder.class, "recordFailure")
                .getAnnotation(Transactional.class);
        assertThat(failure).as("recordFailure に @Transactional があること").isNotNull();
        assertThat(failure.propagation())
                .as("recordFailure は REQUIRES_NEW（万一外側TXから呼ばれても巻き添えで消えないため）")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
