package com.mannschaft.app.search;

import com.mannschaft.app.common.architecture.ProductionClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-C17: search ドメインは集約のみを担い、他ドメインの Repository を直接 DI しない
 * （設計書 §1「アーキテクチャ（SearchContributor 契約）」）。旧 GlobalSearchService は撤去される。
 *
 * <p>凍結ストア（archunit_store）を使わない素の ArchUnit 規則。本番クラスは共有ホルダ
 * {@link ProductionClasses} から得て（CMP-261002-1606）、規則の対象を search 配下に絞り、
 * 既存違反の凍結に頼らず「ゼロ」を固定する（全体の D-5 番人 {@code CrossDomainRepositoryDependencyArchTest}
 * の凍結分は旧実装の撤去で減る）。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("search ドメイン境界（AC-C17 試練）")
class SearchDomainBoundaryTest {

    /** 本番クラス全体（JVM 内で 1 回だけ取り込む共有ホルダ。規則側で search 配下に絞る）。 */
    private final JavaClasses productionClasses = ProductionClasses.get();

    @Test
    @DisplayName("AC-C17: search 配下のクラスは他ドメインの repository パッケージに依存しない")
    void AC_C17_他ドメインRepositoryに依存しない() {
        noClasses().that().resideInAPackage("com.mannschaft.app.search..")
                .should().dependOnClassesThat(resideInAPackage("com.mannschaft.app..repository..")
                        .and(not(resideInAPackage("com.mannschaft.app.search.."))))
                .because("search は SearchContributor 契約の集約のみを担い、各種別の検索は各ドメインが実装する")
                .check(productionClasses);
    }

    /** {@code com.mannschaft.app.<domain>.} の domain 部分。 */
    private static final Pattern DOMAIN = Pattern.compile("^com\\.mannschaft\\.app\\.([^.]+)\\..*");

    /** 本番の {@link SearchContributor} 実装クラス（インタフェース自身を除く）。 */
    private List<JavaClass> contributorImplementations() {
        return productionClasses.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(SearchContributor.class))
                .toList();
    }

    @Test
    @DisplayName("CMP-261116-0722 PR-2: POST 種別の SearchContributor が timeline ドメイン内に実装されている")
    void PR2_POSTのContributorはtimelineドメインにある() {
        List<String> timelineContributors = contributorImplementations().stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.timeline"))
                .map(JavaClass::getName)
                .toList();
        assertThat(timelineContributors)
                .as("timeline ドメインに POST 用 SearchContributor が 1 つあること（search 側に実装しない）")
                .hasSize(1);
    }

    @Test
    @DisplayName("CMP-261116-0722 PR-3: MESSAGE 種別の SearchContributor が chat ドメイン内に実装されている")
    void PR3_MESSAGEのContributorはchatドメインにある() {
        List<String> chatContributors = contributorImplementations().stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.chat"))
                .map(JavaClass::getName)
                .toList();
        assertThat(chatContributors)
                .as("chat ドメインに MESSAGE 用 SearchContributor が 1 つあること（search 側に実装しない）")
                .hasSize(1);
    }

    @Test
    @DisplayName("CMP-261116-0722 PR-4: THREAD 種別の SearchContributor が bulletin ドメイン内に実装されている")
    void PR4_THREADのContributorはbulletinドメインにある() {
        List<String> bulletinContributors = contributorImplementations().stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.bulletin"))
                .map(JavaClass::getName)
                .toList();
        assertThat(bulletinContributors)
                .as("bulletin ドメインに THREAD 用 SearchContributor が 1 つあること（search 側に実装しない）")
                .hasSize(1);
    }

    @Test
    @DisplayName("CMP-261116-0722 PR-5: ARTICLE 種別の SearchContributor が cms ドメイン内に実装されている")
    void PR5_ARTICLEのContributorはcmsドメインにある() {
        List<String> cmsContributors = contributorImplementations().stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.cms"))
                .map(JavaClass::getName)
                .toList();
        assertThat(cmsContributors)
                .as("cms ドメインに ARTICLE 用 SearchContributor が 1 つあること（search 側に実装しない）")
                .hasSize(1);
    }

    @Test
    @DisplayName("CMP-261116-0722 PR-6: FILE 種別の SearchContributor が filesharing ドメイン内に実装されている")
    void PR6_FILEのContributorはfilesharingドメインにある() {
        List<String> filesharingContributors = contributorImplementations().stream()
                .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.filesharing"))
                .map(JavaClass::getName)
                .toList();
        assertThat(filesharingContributors)
                .as("filesharing ドメインに FILE 用 SearchContributor が 1 つあること（search 側に実装しない）")
                .hasSize(1);
    }

    @Test
    @DisplayName("D3: 各 SearchContributor 実装は自ドメイン外の repository パッケージに直接依存しない")
    void D3_Contributorは自ドメイン外のRepositoryに依存しない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass contributor : contributorImplementations()) {
            String own = domainOf(contributor.getPackageName());
            for (Dependency dependency : contributor.getDirectDependenciesFromSelf()) {
                String targetPackage = dependency.getTargetClass().getPackageName();
                if (!targetPackage.contains(".repository")) {
                    continue;
                }
                String target = domainOf(targetPackage);
                if (target != null && !target.equals(own)) {
                    violations.add(contributor.getSimpleName() + " -> " + dependency.getTargetClass().getName());
                }
            }
        }
        assertThat(violations).as("SearchContributor は自ドメインの Repository だけを使う（他ドメインは公開 Service 経由）")
                .isEmpty();
    }

    private static String domainOf(String packageName) {
        Matcher m = DOMAIN.matcher(packageName + ".");
        return m.matches() ? m.group(1) : null;
    }

    @Test
    @DisplayName("AC-C17: 旧 GlobalSearchService（業務データ9種別の横断検索）は撤去されている")
    void AC_C17_旧サービスは撤去() {
        assertThatThrownBy(() -> Class.forName("com.mannschaft.app.search.service.GlobalSearchService"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
