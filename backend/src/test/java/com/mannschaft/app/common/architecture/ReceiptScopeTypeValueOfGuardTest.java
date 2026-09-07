package com.mannschaft.app.common.architecture;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code ReceiptScopeType.valueOf(...)} の直接呼び出しを禁じる番人（F08.4 §9.1.1 D-5 の再発防止）。
 *
 * <h2>守るバグ</h2>
 * <p>{@link ReceiptScopeType} は未知値を業務例外（400 / COMMON_001）へ変換する
 * {@link ReceiptScopeType#from(String)} / {@link ReceiptScopeType#fromTenantScope(String)} を
 * 備えているにもかかわらず、実際に使っていたのは発行者設定コントローラ 1 箇所だけで、
 * 他の 24 箇所は生の {@code ReceiptScopeType.valueOf(scopeType.toUpperCase())} のままだった。
 * その結果、クエリに未知の {@code scopeType} を渡すと {@link IllegalArgumentException} が
 * 素通りして 500 になり（ロケール未指定の {@code toUpperCase()} という別の欠陥も同時に抱えていた）、
 * F08.12 の実機 E2E で発覚した。</p>
 *
 * <h2>なぜ ArchUnit か</h2>
 * <p>「安全なファクトリが用意されているのに使われない」は、個別エンドポイントのテストでは
 * <b>新しく追加されたエンドポイントを守れない</b>。呼び出しそのものを構造で禁じれば、
 * 将来 receipt に生えるどのクラスからも同じ事故が起こらない。</p>
 *
 * <h2>凍結（FreezingArchRule）を使わない理由</h2>
 * <p>本 PR で違反ゼロにするため凍結の必要が無く、凍結すると将来の違反が「既存扱い」で
 * 素通りしかねない。</p>
 */
@AnalyzeClasses(
    packages = "com.mannschaft.app",
    importOptions = ImportOption.DoNotIncludeTests.class
)
class ReceiptScopeTypeValueOfGuardTest {

    /** 唯一 {@code valueOf} を呼んでよいクラス（安全なファクトリの実装そのもの）。 */
    private static final String FACTORY_OWNER = ReceiptScopeType.class.getName();

    @ArchTest
    static final ArchRule no_direct_valueOf_call_on_receipt_scope_type =
        noClasses().that(areNotTheEnumItself())
            .should(new com.tngtech.archunit.lang.ArchCondition<JavaClass>(
                "call " + ReceiptScopeType.class.getSimpleName() + ".valueOf(String) directly") {
                @Override
                public void check(JavaClass javaClass, com.tngtech.archunit.lang.ConditionEvents events) {
                    for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                        if (isReceiptScopeTypeValueOf(call)) {
                            events.add(com.tngtech.archunit.lang.SimpleConditionEvent.satisfied(
                                javaClass,
                                javaClass.getFullName() + " calls ReceiptScopeType.valueOf at "
                                    + call.getSourceCodeLocation()
                                    + " — use ReceiptScopeType.from(String) / fromTenantScope(String) instead"));
                        }
                    }
                }
            })
            .allowEmptyShould(true)
            .because("F08.4 §9.1.1 D-5 — ReceiptScopeType.valueOf を直接呼ぶと未知の scopeType が "
                + "IllegalArgumentException となり 500 になる。クエリ由来の文字列は必ず "
                + "ReceiptScopeType.from(String)（PLATFORM も許すもの）または "
                + "ReceiptScopeType.fromTenantScope(String)（テナントスコープ限定）で解決し、"
                + "400 / COMMON_001 へ変換すること")
            .as("no production class other than ReceiptScopeType itself should call ReceiptScopeType.valueOf");

    private static boolean isReceiptScopeTypeValueOf(JavaMethodCall call) {
        return call.getTargetOwner().getFullName().equals(FACTORY_OWNER)
            && "valueOf".equals(call.getName());
    }

    private static DescribedPredicate<JavaClass> areNotTheEnumItself() {
        return new DescribedPredicate<>("not be ReceiptScopeType itself") {
            @Override
            public boolean test(JavaClass javaClass) {
                return !javaClass.getFullName().equals(FACTORY_OWNER);
            }
        };
    }
}
