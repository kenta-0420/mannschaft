package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.alic.AlicAllowedDelegatedCaller;
import com.mannschaft.app.common.architecture.fixtures.alic.AlicDelegatedTarget;
import com.mannschaft.app.common.architecture.fixtures.alic.AlicRogueDelegatedCaller;
import com.mannschaft.app.common.architecture.fixtures.alic.AlicRogueReadWriter;
import com.mannschaft.app.common.architecture.fixtures.alic.AlicTransactionalClassFreshRoleCaller;
import com.mannschaft.app.common.architecture.fixtures.alic.AlicTransactionalMethodFreshRoleCaller;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261011-0035 ALIC-1 の番人（AC-F02・F03・F04。試練 red）。
 *
 * <p>凍結ストアを使わない普通の検査（{@code FreezingArchRule} 不使用・{@code archunit_store} に触れない）。
 * 各規則は「本番に違反がない」に加えて、テスト専用のダミー（{@code fixtures.alic}）で
 * <b>負例を検出できること</b>、本番に<b>許可された辺が実在すること（空振りしない）</b>を確かめる。
 * 規則が参照する出陣で作る型・メソッドは文字列で持つ（コンパイルを壊さない）。</p>
 *
 * <ul>
 *   <li>F02: 委任入口（メソッド名に {@value #DELEGATED_MARK} を含む）は social.announcement の正規経路からだけ呼ぶ。</li>
 *   <li>F03: 既読の書き込み3メソッドは許可された3辺からだけ呼ぶ（辺単位の許可リスト）。</li>
 *   <li>F04: キャッシュなし役割解決と {@code OrgAnnouncementAudienceAccess} を {@code @Transactional} の
 *       クラス・メソッドから呼ばない。</li>
 * </ul>
 *
 * <p>F01（D-1・D-3・D-3T・D-5 の凍結ストア差分が削除行のみ）はここでは検査しない。出陣後の検分で
 * {@code git diff origin/main -- backend/src/test/resources/archunit_store/} が削除行だけであることを確かめる。</p>
 */
@DisplayName("CMP-261011-0035 ALIC-1 委任入口・既読書き込み・tx 境界の番人")
@Tag(ArchUnitTestTag.ARCHUNIT)
class AnnouncementDelegatedAccessGuardArchTest {

    static final String DELEGATED_MARK = "Delegated";

    // ───────── 対象（出陣で作る型は文字列で持つ） ─────────
    static final String RESOLVER = "com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver";
    static final String BLOG_PREVIEW = "com.mannschaft.app.cms.service.BlogPostPreviewService";
    static final String BULLETIN_FACADE = "com.mannschaft.app.bulletin.service.BulletinReadFacade";
    static final String BULLETIN_READER = "com.mannschaft.app.bulletin.service.BulletinAnnouncementPreviewReader";
    static final String PREVIEW_SOURCE = "com.mannschaft.app.social.announcement.AnnouncementPreviewSourceService";

    static final String FEED_SERVICE = "com.mannschaft.app.social.announcement.AnnouncementFeedService";
    static final String READ_SERVICE = "com.mannschaft.app.social.announcement.AnnouncementReadService";
    static final String READ_COMMAND = "com.mannschaft.app.social.announcement.AnnouncementReadCommandService";

    static final String ROLE_RESOLVER = "com.mannschaft.app.dashboard.service.RoleResolver";
    static final String AUDIENCE_ACCESS = "com.mannschaft.app.social.announcement.OrgAnnouncementAudienceAccess";

    private static final Set<String> TX_ANNOTATIONS = Set.of(
            "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    // ════════════════════════════════════════ F02 ════════════════════════════════════════

    @Nested
    @DisplayName("AC-ALIC1-F02 委任入口は正規経路からだけ呼ぶ")
    class F02 {

        @Test
        @DisplayName("負例: 正規経路以外のダミーから委任入口を呼ぶと検出し、正規経路・委任でない入口は検出しない")
        void 負例を検出する() {
            JavaClasses fixtures = new ClassFileImporter().importClasses(
                    AlicDelegatedTarget.class, AlicAllowedDelegatedCaller.class, AlicRogueDelegatedCaller.class);
            Set<String> violations = callerViolations(fixtures,
                    owner -> owner.getFullName().equals(AlicDelegatedTarget.class.getName()),
                    name -> name.contains(DELEGATED_MARK),
                    Set.of(AlicAllowedDelegatedCaller.class.getName()));
            assertThat(violations).containsExactly(
                    AlicRogueDelegatedCaller.class.getName() + "#detail -> "
                            + AlicDelegatedTarget.class.getName() + "#readDelegated");
        }

        @Test
        @DisplayName("本番: 委任入口を正規経路以外から呼ばない（Resolver・BlogPostPreviewService・BulletinReadFacade・BulletinAnnouncementPreviewReader）")
        void 本番に違反がない() {
            JavaClasses production = ProductionClasses.get();
            Set<String> violations = new TreeSet<>();
            violations.addAll(callerViolations(production, F02::isResolverHierarchy,
                    name -> name.contains(DELEGATED_MARK), Set.of(BLOG_PREVIEW, BULLETIN_FACADE, BULLETIN_READER)));
            violations.addAll(callerViolations(production, owner -> owner.getFullName().equals(BLOG_PREVIEW),
                    name -> name.contains(DELEGATED_MARK), Set.of(PREVIEW_SOURCE)));
            violations.addAll(callerViolations(production, owner -> owner.getFullName().equals(BULLETIN_FACADE),
                    name -> name.contains(DELEGATED_MARK), Set.of(PREVIEW_SOURCE)));
            violations.addAll(callerViolations(production, owner -> owner.getFullName().equals(BULLETIN_READER),
                    name -> !name.startsWith("<"), Set.of(BULLETIN_FACADE)));
            assertThat(violations).as("委任入口の正規経路外の呼び出し").isEmpty();
        }

        @Test
        @DisplayName("正例: 正規経路 AnnouncementPreviewSourceService→Facade/PreviewService→Resolver の委任の辺が本番に実在する（規則が空振りしない）")
        void 正規経路の辺が実在する() {
            JavaClasses production = ProductionClasses.get();
            assertThat(edges(production, owner -> owner.getFullName().equals(BLOG_PREVIEW),
                    name -> name.contains(DELEGATED_MARK)))
                    .as("AnnouncementPreviewSourceService → BlogPostPreviewService#…Delegated…")
                    .anyMatch(edge -> edge.startsWith(PREVIEW_SOURCE + "#"));
            assertThat(edges(production, owner -> owner.getFullName().equals(BULLETIN_FACADE),
                    name -> name.contains(DELEGATED_MARK)))
                    .as("AnnouncementPreviewSourceService → BulletinReadFacade#…Delegated…")
                    .anyMatch(edge -> edge.startsWith(PREVIEW_SOURCE + "#"));
            assertThat(edges(production, owner -> owner.getFullName().equals(BULLETIN_READER), name -> !name.startsWith("<")))
                    .as("BulletinReadFacade → BulletinAnnouncementPreviewReader（bulletin の tx なし読取部品）")
                    .anyMatch(edge -> edge.startsWith(BULLETIN_FACADE + "#"));
            List<String> resolverEdges = edges(production, F02::isResolverHierarchy, name -> name.contains(DELEGATED_MARK));
            assertThat(resolverEdges).as("cms 側: BlogPostPreviewService → Resolver#…Delegated…")
                    .anyMatch(edge -> edge.startsWith(BLOG_PREVIEW + "#"));
            assertThat(resolverEdges).as("bulletin 側: Facade/Reader → Resolver#…Delegated…（F00 を委任役割で評価）")
                    .anyMatch(edge -> edge.startsWith(BULLETIN_FACADE + "#") || edge.startsWith(BULLETIN_READER + "#"));
        }

        private static boolean isResolverHierarchy(JavaClass owner) {
            return owner.getFullName().equals(RESOLVER) || owner.isAssignableTo(RESOLVER);
        }
    }

    // ════════════════════════════════════════ F03 ════════════════════════════════════════

    @Nested
    @DisplayName("AC-ALIC1-F03 既読の書き込みは許可3辺からだけ呼ぶ")
    class F03 {

        /** 許可辺（呼出元クラス#呼出元メソッド（* は任意） -> 呼出先クラス#メソッド）。 */
        static final Set<String> ALLOWED = Set.of(
                READ_COMMAND + "#* -> " + FEED_SERVICE + "#markAsRead",
                FEED_SERVICE + "#markAsRead -> " + READ_SERVICE + "#markAsRead",
                READ_COMMAND + "#* -> " + READ_SERVICE + "#markFeedIdsAsRead");

        @Test
        @DisplayName("負例: 判定部品を経ずに FeedService.markAsRead・ReadService.markAsRead を呼ぶダミーを2辺とも検出する")
        void 負例を検出する() {
            JavaClasses fixtures = new ClassFileImporter().importClasses(AlicRogueReadWriter.class);
            assertThat(violations(fixtures)).containsExactlyInAnyOrder(
                    AlicRogueReadWriter.class.getName() + "#bypass -> " + FEED_SERVICE + "#markAsRead",
                    AlicRogueReadWriter.class.getName() + "#bypass -> " + READ_SERVICE + "#markAsRead");
        }

        @Test
        @DisplayName("本番: 許可3辺以外（コントローラー・他の social サービス等）から既読の書き込みを呼ばない")
        void 本番に違反がない() {
            assertThat(violations(ProductionClasses.get())).as("許可されない既読書き込みの辺").isEmpty();
        }

        @Test
        @DisplayName("正例: 許可3辺が本番に実在する（規則が空振りしない）")
        void 許可3辺が実在する() {
            Set<String> actual = rawEdges(ProductionClasses.get()).stream()
                    .map(F03::generalize).collect(Collectors.toCollection(TreeSet::new));
            assertThat(actual).as("既読書き込みの実在辺").containsAll(ALLOWED);
        }

        private static Set<String> violations(JavaClasses classes) {
            return rawEdges(classes).stream()
                    .filter(edge -> !ALLOWED.contains(generalize(edge)))
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        private static List<String> rawEdges(JavaClasses classes) {
            return calls(classes)
                    .filter(call -> isWriteTarget(call.getTarget().getOwner().getFullName(), call.getTarget().getName()))
                    .map(AnnouncementDelegatedAccessGuardArchTest::edge)
                    .distinct().toList();
        }

        private static boolean isWriteTarget(String owner, String method) {
            return (owner.equals(FEED_SERVICE) && method.equals("markAsRead"))
                    || (owner.equals(READ_SERVICE) && (method.equals("markAsRead") || method.equals("markFeedIdsAsRead")));
        }

        /** 判定部品からの辺は呼出元メソッドを問わない（* に寄せる）。 */
        private static String generalize(String edge) {
            return edge.startsWith(READ_COMMAND + "#") ? READ_COMMAND + "#*" + edge.substring(edge.indexOf(" -> ")) : edge;
        }
    }

    // ════════════════════════════════════════ F04 ════════════════════════════════════════

    @Nested
    @DisplayName("AC-ALIC1-F04 キャッシュなし役割解決と宛先資格判定を @Transactional の中から呼ばない")
    class F04 {

        @Test
        @DisplayName("負例: クラス全体 tx・メソッド tx のダミーからの呼び出しを検出し、tx のないメソッドは検出しない")
        void 負例を検出する() {
            JavaClasses fixtures = new ClassFileImporter().importClasses(
                    AlicTransactionalClassFreshRoleCaller.class, AlicTransactionalMethodFreshRoleCaller.class);
            assertThat(violations(fixtures)).containsExactlyInAnyOrder(
                    AlicTransactionalClassFreshRoleCaller.class.getName() + "#resolve -> "
                            + ROLE_RESOLVER + "#resolveViewerRolesFresh",
                    AlicTransactionalMethodFreshRoleCaller.class.getName() + "#insideTx -> "
                            + ROLE_RESOLVER + "#resolveViewerRoleForPreview");
        }

        @Test
        @DisplayName("本番: @Transactional のクラス・メソッドからキャッシュなし役割解決・OrgAnnouncementAudienceAccess を呼ばない")
        void 本番に違反がない() {
            assertThat(violations(ProductionClasses.get())).as("tx 内からの呼び出し").isEmpty();
        }

        @Test
        @DisplayName("正例: OrgAnnouncementAudienceAccess が本番に実在し、tx を持たず、呼び出し元がある（規則が空振りしない）")
        void 宛先資格判定が実在する() {
            JavaClasses production = ProductionClasses.get();
            assertThat(production.contain(AUDIENCE_ACCESS)).as("出陣で作る %s", AUDIENCE_ACCESS).isTrue();
            JavaClass access = production.get(AUDIENCE_ACCESS);
            assertThat(isTransactional(access)).as("判定部品はクラスに tx を持たない").isFalse();
            assertThat(access.getMethods()).noneMatch(F04::isTransactional);
            assertThat(edges(production, owner -> owner.getFullName().equals(AUDIENCE_ACCESS), name -> !name.startsWith("<")))
                    .as("呼び出し元（プレビュー・既読判定）").isNotEmpty();
        }

        private static Set<String> violations(JavaClasses classes) {
            return calls(classes)
                    .filter(call -> isFreshTarget(call.getTarget().getOwner().getFullName(), call.getTarget().getName()))
                    .filter(call -> isTransactional(call.getOrigin()) || isTransactional(call.getOrigin().getOwner()))
                    .map(AnnouncementDelegatedAccessGuardArchTest::edge)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        private static boolean isFreshTarget(String owner, String method) {
            return (owner.equals(ROLE_RESOLVER)
                    && (method.equals("resolveViewerRolesFresh") || method.equals("resolveViewerRoleForPreview")))
                    || (owner.equals(AUDIENCE_ACCESS) && !method.startsWith("<"));
        }

        private static boolean isTransactional(JavaCodeUnit unit) {
            return TX_ANNOTATIONS.stream().anyMatch(unit::isAnnotatedWith);
        }

        private static boolean isTransactional(JavaClass owner) {
            return TX_ANNOTATIONS.stream().anyMatch(owner::isAnnotatedWith);
        }
    }

    // ════════════════════════════════════════ 共通 ════════════════════════════════════════

    private static java.util.stream.Stream<JavaMethodCall> calls(JavaClasses classes) {
        return classes.stream().flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream());
    }

    /** 「呼出元トップレベルクラス#呼出元メソッド -> 呼出先クラス#メソッド」。lambda は囲むメソッド名に戻す。 */
    static String edge(JavaMethodCall call) {
        return topLevel(call.getOrigin().getOwner().getFullName()) + "#" + originName(call.getOrigin().getName())
                + " -> " + call.getTarget().getOwner().getFullName() + "#" + call.getTarget().getName();
    }

    /** 対象への呼び出し辺のうち、許可呼出元・対象自身（同一クラス階層）以外からのもの。 */
    static Set<String> callerViolations(JavaClasses classes, Predicate<JavaClass> targetOwner,
                                        Predicate<String> methodName, Set<String> allowedCallers) {
        return calls(classes)
                .filter(call -> targetOwner.test(call.getTarget().getOwner()) && methodName.test(call.getTarget().getName()))
                .filter(call -> {
                    String caller = topLevel(call.getOrigin().getOwner().getFullName());
                    return !allowedCallers.contains(caller) && !targetOwner.test(call.getOrigin().getOwner());
                })
                .map(AnnouncementDelegatedAccessGuardArchTest::edge)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static List<String> edges(JavaClasses classes, Predicate<JavaClass> targetOwner, Predicate<String> methodName) {
        return calls(classes)
                .filter(call -> targetOwner.test(call.getTarget().getOwner()) && methodName.test(call.getTarget().getName()))
                .map(AnnouncementDelegatedAccessGuardArchTest::edge)
                .distinct().toList();
    }

    private static String topLevel(String fullName) {
        int dollar = fullName.indexOf('$');
        return dollar < 0 ? fullName : fullName.substring(0, dollar);
    }

    private static String originName(String name) {
        if (name.startsWith("lambda$")) {
            String[] parts = name.split("\\$");
            return parts.length > 1 ? parts[1] : name;
        }
        return name;
    }
}
