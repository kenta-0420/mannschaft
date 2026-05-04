package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ContentVisibilityChecker} ファサードの単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.4 / §7.1〜§7.4。
 *
 * <p>本テストは Resolver を Mockito ではなく手書きスタブで差し込み、
 * 「ファサードのディスパッチ責務」のみを純粋に検証する。
 */
@DisplayName("ContentVisibilityChecker ファサード")
class ContentVisibilityCheckerTest {

    @Nested
    @DisplayName("構築 (constructor)")
    class Construction {

        @Test
        @DisplayName("Resolver 0 個でもファサードを構築できる (Smoke)")
        void smoke_emptyResolverList() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of());

            assertThat(checker).isNotNull();
            // 未対応扱いとして fail-closed で false が返る
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("複数の異なる referenceType の Resolver は正しく登録される")
        void registers_multipleResolversForDifferentTypes() {
            StubResolver blog = new StubResolver(ReferenceType.BLOG_POST, Set.of(1L));
            StubResolver event = new StubResolver(ReferenceType.EVENT, Set.of(2L));

            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog, event));

            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 99L)).isTrue();
            assertThat(checker.canView(ReferenceType.EVENT, 2L, 99L)).isTrue();
        }

        @Test
        @DisplayName("同一 referenceType の重複登録は IllegalStateException")
        void throws_whenDuplicateReferenceType() {
            StubResolver a = new StubResolver(ReferenceType.BLOG_POST, Set.of(1L));
            StubResolver b = new StubResolver(ReferenceType.BLOG_POST, Set.of(2L));

            assertThatThrownBy(() -> new ContentVisibilityChecker(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("BLOG_POST");
        }
    }

    @Nested
    @DisplayName("未対応 ReferenceType の fail-closed 挙動")
    class FailClosed {

        private final ContentVisibilityChecker checker =
            new ContentVisibilityChecker(List.of());

        @Test
        @DisplayName("canView は false を返す")
        void canView_returnsFalseForUnsupportedType() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("filterAccessible は空 Set を返す")
        void filterAccessible_returnsEmptySetForUnsupportedType() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), 100L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("decide は UNSUPPORTED_REFERENCE_TYPE で deny を返す")
        void decide_returnsDenyWithUnsupportedReason() {
            VisibilityDecision decision =
                checker.decide(ReferenceType.PERSONAL_TIMETABLE, 7L, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason())
                .isEqualTo(DenyReason.UNSUPPORTED_REFERENCE_TYPE);
            assertThat(decision.referenceType())
                .isEqualTo(ReferenceType.PERSONAL_TIMETABLE);
            assertThat(decision.contentId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("assertCanView は BusinessException をスローする")
        void assertCanView_throwsBusinessExceptionForUnsupportedType() {
            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.FOLLOW_LIST, 1L, 100L))
                .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("ディスパッチ (登録された Resolver への委譲)")
    class Dispatch {

        @Test
        @DisplayName("canView が登録 Resolver の判定結果をそのまま返す")
        void canView_dispatchesToResolver() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 3L, 5L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver));

            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isTrue();
            assertThat(checker.canView(ReferenceType.BLOG_POST, 2L, 100L)).isFalse();
            assertThat(checker.canView(ReferenceType.BLOG_POST, 5L, 100L)).isTrue();
        }

        @Test
        @DisplayName("filterAccessible が許可 ID のみを残して返す")
        void filterAccessible_dispatchesToResolver() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 3L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver));

            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L, 4L), 100L);

            assertThat(result).containsExactlyInAnyOrder(1L, 3L);
        }

        @Test
        @DisplayName("decide はデフォルト実装で allow/deny を返す")
        void decide_returnsAllowOrDenyViaDefaultImpl() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver));

            VisibilityDecision allowed =
                checker.decide(ReferenceType.BLOG_POST, 1L, 100L);
            VisibilityDecision denied =
                checker.decide(ReferenceType.BLOG_POST, 2L, 100L);

            assertThat(allowed.allowed()).isTrue();
            assertThat(allowed.denyReason()).isNull();
            assertThat(denied.allowed()).isFalse();
            // デフォルト実装は UNSPECIFIED で deny する
            assertThat(denied.denyReason()).isEqualTo(DenyReason.UNSPECIFIED);
        }

        @Test
        @DisplayName("assertCanView は許可なら例外を投げない")
        void assertCanView_doesNotThrowWhenAllowed() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver));

            // 例外が出ないことを確認
            checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L);
        }

        @Test
        @DisplayName("assertCanView は拒否なら BusinessException を投げる")
        void assertCanView_throwsWhenDenied() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver));

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 999L, 100L))
                .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("filterAccessibleByType — 複数 type 同時判定")
    class MultiTypeDispatch {

        @Test
        @DisplayName("登録済み複数 type の混在判定が type ごとに正しく分配される")
        void filterAccessibleByType_dispatchesEachType() {
            StubResolver blog = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 2L));
            StubResolver event = new StubResolver(
                ReferenceType.EVENT, Set.of(20L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog, event));

            Map<ReferenceType, Collection<Long>> input =
                new EnumMap<>(ReferenceType.class);
            input.put(ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));
            input.put(ReferenceType.EVENT, List.of(10L, 20L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result)
                .containsEntry(ReferenceType.BLOG_POST, Set.of(1L, 2L))
                .containsEntry(ReferenceType.EVENT, Set.of(20L));
        }

        @Test
        @DisplayName("未対応 type が混じっていても、その type は空 Set にマップされる")
        void filterAccessibleByType_unsupportedTypeYieldsEmpty() {
            StubResolver blog = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog));

            Map<ReferenceType, Collection<Long>> input =
                new EnumMap<>(ReferenceType.class);
            input.put(ReferenceType.BLOG_POST, List.of(1L, 2L));
            input.put(ReferenceType.SCHEDULE, List.of(50L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result).containsEntry(ReferenceType.BLOG_POST, Set.of(1L));
            assertThat(result.get(ReferenceType.SCHEDULE)).isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    // テスト用 Resolver スタブ — 「許可される ID 集合」を渡して挙動を制御
    // ---------------------------------------------------------------------

    /**
     * 指定された contentId だけを許可し、それ以外を拒否する単純な Resolver スタブ。
     * Mockito を使わず、ファサードのディスパッチ責務を純粋に検証する目的で利用する。
     */
    private static final class StubResolver
            implements ContentVisibilityResolver<String> {

        private final ReferenceType type;
        private final Set<Long> allowedIds;

        StubResolver(ReferenceType type, Set<Long> allowedIds) {
            this.type = type;
            this.allowedIds = allowedIds;
        }

        @Override
        public ReferenceType referenceType() {
            return type;
        }

        @Override
        public boolean canView(Long contentId, Long viewerUserId) {
            return allowedIds.contains(contentId);
        }

        @Override
        public Set<Long> filterAccessible(
                Collection<Long> contentIds, Long viewerUserId) {
            return contentIds.stream()
                .filter(allowedIds::contains)
                .collect(java.util.stream.Collectors.toSet());
        }
    }
}
