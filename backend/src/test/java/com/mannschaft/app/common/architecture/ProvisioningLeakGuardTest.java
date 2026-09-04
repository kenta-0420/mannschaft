package com.mannschaft.app.common.architecture;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱②-3 販促プロビジョニングゲート番人（検分 P1-4 根治版）。
 *
 * <p>旧実装は {@code TeamRepository} / {@code OrganizationRepository} のソースを
 * 「{@code Visibility.PUBLIC} を含む行の前後15行テキスト窓」で検査していたため、
 * derived query（{@code findBySlugAndDeletedAtIsNull} 等、{@code @Query} を持たない
 * メソッド）を一切検査できず、slug 解決の PROVISIONED 漏出（検分 P1-2）を検出できなかった
 * （偽陰性）。</p>
 *
 * <h2>本版の方式（クエリ単位・deny-by-default）</h2>
 * <p>15行窓のテキストマッチではなく、{@link Class#getDeclaredMethods()} で
 * リポジトリインタフェースが宣言する<strong>全メソッド</strong>を列挙し、メソッド単位で
 * 判定する（{@code @Query} を持つメソッドは {@link Query#value()} を直接読み、
 * derived query メソッドはメソッド名自体を判定対象とする。行番号や窓幅に依存しない）。</p>
 *
 * <ol>
 *   <li><b>公開系判定</b>: メソッド名に {@code Public} を含む（公開検索・sitemap・discover）、
 *       {@code BySlug} を含む（URL識別子からの解決＝スコープ存在自体が漏れる）、
 *       {@code searchByKeyword}（公開検索の実体）、{@code findChildrenPage}（階層公開表示）
 *       のいずれか、または {@code @Query} 本文に {@code Visibility.PUBLIC} を含む場合を
 *       「公開系」と判定する。</li>
 *   <li><b>ACTIVE安全判定</b>: {@code @Query} を持つ場合は本文に {@code LifecycleStatus.ACTIVE}
 *       を含むこと。derived query の場合はメソッド名に {@code LifecycleStatus} を含む
 *       （= lifecycle_status を絞り込みパラメータとして持つ）こと。</li>
 *   <li><b>deny-by-default</b>: 公開系と判定されたメソッドが ACTIVE 安全でない場合、
 *       {@link #ALLOWLIST} に「非公開経路」として明示登録されていない限り fail する。
 *       新規メソッド追加時、ALLOWLIST に載せない限り自動的に検査対象へ入るため、
 *       レビューの見落としに頼らない（検出器は自分の偽陰性を最初に晒す、の戒め対応）。</li>
 * </ol>
 *
 * <p><b>既知の残存債務（ALLOWLIST に理由付きで明示登録・別タスク対象）</b>:
 * {@code findBySlugAndDeletedAtIsNull} は本 PR 以前から
 * {@code BulletinScopeIdResolver} / {@code BlogPostService} が直接使用しており、
 * これらも理論上は PROVISIONED スコープを解決し得る（本 PR の是正対象は
 * {@code TeamService#resolveTeamId} / {@code OrganizationService#resolveOrgId} の
 * 入口のみ）。凍結値の単純カウントで隠さず、ALLOWLIST に理由を明記した上で
 * 追跡できるようにする。{@code existsBySlugAndDeletedAtIsNull} は真偽値のみを返し
 * スコープの内容を漏らさないため、PROVISIONED を含めた一意性判定が正しい仕様である。</p>
 */
class ProvisioningLeakGuardTest {

    /**
     * 「非公開経路」と明示宣言する（ACTIVE 安全でなくても許容する）メソッドの allowlist。
     * key = 単純クラス名、value = 許容メソッド名の集合。
     */
    private static final Map<String, Set<String>> ALLOWLIST = Map.of(
            "TeamRepository", Set.of(
                    // 真偽値のみを返し、内容を漏らさない一意性チェック（PROVISIONED込みで正しい仕様）。
                    "existsBySlugAndDeletedAtIsNull",
                    // 既存債務: BulletinScopeIdResolver / BlogPostService が直接使用中。
                    // 是正は別タスク（本 PR は resolveTeamId の入口のみを対象とする）。
                    "findBySlugAndDeletedAtIsNull"
            ),
            "OrganizationRepository", Set.of(
                    "existsBySlugAndDeletedAtIsNull",
                    "findBySlugAndDeletedAtIsNull"
            )
    );

    @Test
    @DisplayName("柱②-3: TeamRepositoryの公開系メソッドは全てACTIVE安全（deny-by-default・クエリ単位判定）")
    void teamRepositoryPublicMethodsRequireActiveGate() {
        assertNoLeak(TeamRepository.class);
    }

    @Test
    @DisplayName("柱②-3: OrganizationRepositoryの公開系メソッドは全てACTIVE安全（deny-by-default・クエリ単位判定）")
    void organizationRepositoryPublicMethodsRequireActiveGate() {
        assertNoLeak(OrganizationRepository.class);
    }

    private void assertNoLeak(Class<?> repositoryInterface) {
        Set<String> allowlist = ALLOWLIST.getOrDefault(repositoryInterface.getSimpleName(), Set.of());
        List<String> violations = new ArrayList<>();
        int publicFacingCount = 0;

        for (Method method : repositoryInterface.getDeclaredMethods()) {
            if (!isPublicFacing(method)) {
                continue;
            }
            publicFacingCount++;
            if (isActiveSafe(method)) {
                continue;
            }
            if (allowlist.contains(method.getName())) {
                continue;
            }
            violations.add(repositoryInterface.getSimpleName() + "#" + method.getName()
                    + " — 公開系メソッドに lifecycleStatus=ACTIVE 条件が無く、"
                    + "ALLOWLIST にも未登録（PROVISIONED漏出の恐れ。ACTIVE条件を追加するか、"
                    + "非公開経路として理由付きで ALLOWLIST へ登録すること）");
        }

        assertThat(violations).as("PROVISIONED漏出ゲート違反: %s", violations).isEmpty();
        // 公開系メソッドが 1 件も検出されない場合は判定ロジック自体が壊れている（偽陰性）。
        assertThat(publicFacingCount)
                .as("公開系メソッドが検出できていない（判定ロジックの偽陰性の恐れ）")
                .isGreaterThan(0);
    }

    /** メソッド名または @Query 本文から「公開系」（未認証/未承諾でも到達しうる経路）かを判定する。 */
    private boolean isPublicFacing(Method method) {
        String name = method.getName();
        if (name.contains("Public") || name.contains("BySlug")
                || name.equals("searchByKeyword") || name.equals("findChildrenPage")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        return query != null && query.value().contains("Visibility.PUBLIC");
    }

    /** @Query 本文の ACTIVE 条件、または derived query のメソッド名に LifecycleStatus 条件があるかを判定する。 */
    private boolean isActiveSafe(Method method) {
        Query query = method.getAnnotation(Query.class);
        if (query != null) {
            return query.value().contains("LifecycleStatus.ACTIVE");
        }
        return method.getName().contains("LifecycleStatus");
    }
}
