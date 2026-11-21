package com.mannschaft.app.search;

import com.mannschaft.app.common.architecture.ArchUnitTestTag;
import com.mannschaft.app.common.architecture.ProductionClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回帰固定（CMP-261116-0722 PR-6 検分 P1）: グローバル検索の各種別は、<b>可視性を判定した SQL の行から返却列まで取る</b>。
 *
 * <p>欠陥の型: 可視性述語を持つ生 SQL で ID だけを取り、その後 JPQL（{@code SELECT e FROM XxxEntity e WHERE e.id IN :ids}）
 * で行を読み直していた。トランザクションが無いため、2 本の間に「権限範囲外のスコープへの移動＋本文の更新」が同時に
 * コミットされると、1 本目は移動前の可視性で通過し、2 本目は移動後の本文・メタ情報を結果に載せてしまう
 * （FILE で指摘、THREAD / MESSAGE / ARTICLE / POST にも同型があった）。</p>
 *
 * <p><b>なぜ構造で固定するか</b>: 並行コミットの割り込みは、本番コードにテスト用の介入点を作らない限り IT で決定的に
 * 再現できない。そこで「読み直しが起こりうる形」そのものを禁じる。次のいずれかに戻すと本テストが落ちる。</p>
 * <ul>
 *   <li>検索 Repository が生 SQL（{@code createNativeQuery}）と JPQL／エンティティ取得
 *       （{@code createQuery} / {@code find} / {@code getReference} 等）を併用する。</li>
 *   <li>検索 Repository の結果型（入れ子の record）がエンティティを保持する（＝エンティティを別途読み込んでいる）。</li>
 *   <li>検索 Repository または Contributor が Spring Data Repository・{@link EntityManager} で行を取り直す。</li>
 * </ul>
 * <p>LIKE 系の TEAM / ORGANIZATION / USER は可視性述語と返却エンティティを 1 本の JPQL で取るため（読み直しなし）、
 * JPQL だけを使うこと自体は許す。名前・slug・アバター等の表示名解決（{@code NameResolverService} 等の他ドメイン公開
 * Service）は可視性に関わらないため対象外とする。</p>
 *
 * <p>凍結ストア（archunit_store）を使わない素の ArchUnit 判定（違反ゼロを固定）。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("検索 Contributor の可視性判定と返却データ取得は同一 SQL（CMP-261116-0722 PR-6 検分 P1 回帰固定）")
class SearchContributorSingleQueryFetchTest {

    /** 生 SQL 以外でエンティティ／行を読む {@link EntityManager} の操作。 */
    private static final Set<String> ENTITY_READS = Set.of(
            "createQuery", "createNamedQuery", "find", "getReference", "refresh", "createEntityGraph");

    private final JavaClasses productionClasses = ProductionClasses.get();

    private List<JavaClass> contributors() {
        return productionClasses.stream()
                .filter(c -> !c.isInterface() && c.isAssignableTo(SearchContributor.class))
                .toList();
    }

    /** Contributor が直接使う検索 Repository（{@code *KeywordSearchRepository}）。名前で引ける順に並べる。 */
    private Map<String, JavaClass> keywordSearchRepositories() {
        Map<String, JavaClass> repositories = new TreeMap<>();
        for (JavaClass contributor : contributors()) {
            for (Dependency dependency : contributor.getDirectDependenciesFromSelf()) {
                JavaClass target = topLevel(dependency.getTargetClass());
                if (target.getSimpleName().endsWith("KeywordSearchRepository")) {
                    repositories.put(target.getName(), target);
                }
            }
        }
        return repositories;
    }

    private static JavaClass topLevel(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    @Test
    @DisplayName("前提: FULLTEXT 系 5 種別と ACTIVITY を含む検索 Repository を検出できる（空振りで緑にしない）")
    void 前提_検索Repositoryを検出できる() {
        assertThat(keywordSearchRepositories().keySet()).contains(
                "com.mannschaft.app.filesharing.repository.SharedFileKeywordSearchRepository",
                "com.mannschaft.app.bulletin.repository.BulletinThreadKeywordSearchRepository",
                "com.mannschaft.app.chat.repository.ChatMessageKeywordSearchRepository",
                "com.mannschaft.app.cms.repository.BlogPostKeywordSearchRepository",
                "com.mannschaft.app.timeline.repository.TimelinePostKeywordSearchRepository",
                "com.mannschaft.app.activity.repository.ActivityResultKeywordSearchRepository");
    }

    @Test
    @DisplayName("検索 Repository は生 SQL と JPQL／エンティティ取得を併用しない（ID で読み直さない）")
    void 生SQLとJPQLを併用しない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass repository : keywordSearchRepositories().values()) {
            boolean nativeSql = false;
            List<String> entityReads = new ArrayList<>();
            for (JavaMethodCall call : repository.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().isAssignableTo(EntityManager.class)) {
                    continue;
                }
                String name = call.getTarget().getName();
                if ("createNativeQuery".equals(name)) {
                    nativeSql = true;
                } else if (ENTITY_READS.contains(name)) {
                    entityReads.add(call.getOrigin().getName() + " -> EntityManager#" + name);
                }
            }
            if (nativeSql && !entityReads.isEmpty()) {
                violations.add(repository.getSimpleName() + " " + entityReads);
            }
        }
        assertThat(violations)
                .as("可視性を判定した生 SQL の後に JPQL 等で行を読み直すと、並行更新で判定外の状態を返す")
                .isEmpty();
    }

    @Test
    @DisplayName("生 SQL の検索 Repository の結果型はエンティティを保持しない（返却列は判定と同じ行から取る）")
    void 結果型はエンティティを保持しない() {
        List<String> violations = new ArrayList<>();
        for (JavaClass repository : keywordSearchRepositories().values()) {
            boolean nativeSql = repository.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> c.getTargetOwner().isAssignableTo(EntityManager.class)
                            && "createNativeQuery".equals(c.getTarget().getName()));
            if (!nativeSql) {
                continue;
            }
            for (JavaClass nested : productionClasses.stream()
                    .filter(c -> c.getEnclosingClass().map(e -> e.equals(repository)).orElse(false))
                    .toList()) {
                for (JavaField field : nested.getFields()) {
                    if (field.getRawType().isAnnotatedWith(Entity.class)) {
                        violations.add(nested.getSimpleName() + "#" + field.getName() + " : "
                                + field.getRawType().getSimpleName());
                    }
                }
            }
        }
        assertThat(violations)
                .as("結果型がエンティティを持つ＝判定とは別に行を読み込んでいる（PR-6 検分 P1 の型）")
                .isEmpty();
    }

    @Test
    @DisplayName("検索 Repository・Contributor は Spring Data Repository や EntityManager で行を取り直さない")
    void Spring_DataやEntityManagerで取り直さない() {
        List<String> violations = new ArrayList<>();
        List<JavaClass> targets = new ArrayList<>(contributors());
        targets.addAll(keywordSearchRepositories().values());
        for (JavaClass origin : targets) {
            boolean isRepository = origin.getSimpleName().endsWith("KeywordSearchRepository");
            for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (target.isAssignableTo(org.springframework.data.repository.Repository.class)
                        || (!isRepository && target.isAssignableTo(EntityManager.class))) {
                    violations.add(origin.getSimpleName() + " -> " + target.getName());
                }
            }
        }
        assertThat(violations.stream().distinct().toList())
                .as("可視性判定済みの行以外から本文・メタを読み直さない")
                .isEmpty();
    }
}
