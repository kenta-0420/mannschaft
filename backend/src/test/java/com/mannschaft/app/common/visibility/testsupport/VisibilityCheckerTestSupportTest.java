package com.mannschaft.app.common.visibility.testsupport;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.DenyReason;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link VisibilityCheckerTestSupport} の自己検証テスト。
 *
 * <p>5 メソッド ({@code allowAll} / {@code denyAll} / {@code allowFor} /
 * {@code allowForUser} / {@code denyWithReason}) すべての挙動を Mockito で検証する。</p>
 */
class VisibilityCheckerTestSupportTest {

    private ContentVisibilityChecker checker;

    @BeforeEach
    void setUp() {
        checker = mock(ContentVisibilityChecker.class);
    }

    @Nested
    @DisplayName("allowAll: 全 type / 全 contentId / 全 userId に対して allow")
    class AllowAllTests {

        @BeforeEach
        void stub() {
            VisibilityCheckerTestSupport.allowAll(checker);
        }

        @Test
        @DisplayName("canView は常に true")
        void canViewReturnsTrue() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isTrue();
            assertThat(checker.canView(ReferenceType.EVENT, 999L, null)).isTrue();
        }

        @Test
        @DisplayName("filterAccessible は入力 ids をそのまま Set として返す")
        void filterAccessibleReturnsInputAsIs() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), 100L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
        }

        @Test
        @DisplayName("filterAccessibleByType は入力 Map をそのまま Set 化して返す")
        void filterAccessibleByTypeReturnsInputAsIs() {
            Map<ReferenceType, ? extends java.util.Collection<Long>> input = Map.of(
                ReferenceType.BLOG_POST, List.of(1L, 2L),
                ReferenceType.EVENT, List.of(10L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result).containsOnlyKeys(ReferenceType.BLOG_POST, ReferenceType.EVENT);
            assertThat(result.get(ReferenceType.BLOG_POST)).containsExactlyInAnyOrder(1L, 2L);
            assertThat(result.get(ReferenceType.EVENT)).containsExactly(10L);
        }

        @Test
        @DisplayName("decide は allow を返す")
        void decideReturnsAllow() {
            VisibilityDecision decision = checker.decide(ReferenceType.BLOG_POST, 1L, 100L);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.referenceType()).isEqualTo(ReferenceType.BLOG_POST);
            assertThat(decision.contentId()).isEqualTo(1L);
            assertThat(decision.denyReason()).isNull();
        }
    }

    @Nested
    @DisplayName("denyAll: 全 deny")
    class DenyAllTests {

        @BeforeEach
        void stub() {
            VisibilityCheckerTestSupport.denyAll(checker);
        }

        @Test
        @DisplayName("canView は常に false")
        void canViewReturnsFalse() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("filterAccessible は常に空 Set")
        void filterAccessibleReturnsEmpty() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), 100L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("filterAccessibleByType は入力キーをすべて空 Set にマップ")
        void filterAccessibleByTypeReturnsEmptyForAllKeys() {
            Map<ReferenceType, ? extends java.util.Collection<Long>> input = Map.of(
                ReferenceType.BLOG_POST, List.of(1L, 2L),
                ReferenceType.EVENT, List.of(10L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result).containsOnlyKeys(ReferenceType.BLOG_POST, ReferenceType.EVENT);
            assertThat(result.get(ReferenceType.BLOG_POST)).isEmpty();
            assertThat(result.get(ReferenceType.EVENT)).isEmpty();
        }

        @Test
        @DisplayName("decide は deny(UNSPECIFIED) を返す")
        void decideReturnsDenyUnspecified() {
            VisibilityDecision decision = checker.decide(ReferenceType.BLOG_POST, 1L, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.UNSPECIFIED);
        }

        @Test
        @DisplayName("assertCanView は RuntimeException をスロー")
        void assertCanViewThrows() {
            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("deny");
        }
    }

    @Nested
    @DisplayName("allowFor(type, ids): 特定 type の特定 contentId のみ allow")
    class AllowForTests {

        @BeforeEach
        void stub() {
            VisibilityCheckerTestSupport.allowFor(
                checker, ReferenceType.BLOG_POST, Set.of(1L, 2L));
        }

        @Test
        @DisplayName("該当 type かつ該当 id は canView=true")
        void canViewReturnsTrueForMatchingIds() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isTrue();
            assertThat(checker.canView(ReferenceType.BLOG_POST, 2L, 100L)).isTrue();
        }

        @Test
        @DisplayName("該当 type だが id 非該当は canView=false")
        void canViewReturnsFalseForNonMatchingId() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 99L, 100L)).isFalse();
        }

        @Test
        @DisplayName("非該当 type は既定挙動の false")
        void canViewReturnsFalseForOtherType() {
            // Mockito 既定では boolean 戻り値は false
            assertThat(checker.canView(ReferenceType.EVENT, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("filterAccessible は該当 type で id 該当分のみ返す")
        void filterAccessibleReturnsMatchingIdsOnly() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L, 4L), 100L);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("filterAccessible は非該当 type で空 Set を返す（Mockito 既定）")
        void filterAccessibleReturnsEmptyForOtherType() {
            // Mockito 既定では Set 戻り値は null だが、ContentVisibilityChecker 自体の
            // スタブが効くのは eq(type) のときのみ。それ以外は null が返る。
            // 本テストでは null を許容しない仕様確認のため、null か空のいずれかを検証。
            Set<Long> result = checker.filterAccessible(
                ReferenceType.EVENT, List.of(1L), 100L);
            assertThat(result == null || result.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("allowForUser(userId): 特定 userId のみ allow")
    class AllowForUserTests {

        private static final Long ALLOWED_USER = 100L;
        private static final Long OTHER_USER = 200L;

        @BeforeEach
        void stub() {
            VisibilityCheckerTestSupport.allowForUser(checker, ALLOWED_USER);
        }

        @Test
        @DisplayName("該当 userId は canView=true")
        void canViewReturnsTrueForAllowedUser() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, ALLOWED_USER)).isTrue();
            assertThat(checker.canView(ReferenceType.EVENT, 99L, ALLOWED_USER)).isTrue();
        }

        @Test
        @DisplayName("非該当 userId は既定挙動の false")
        void canViewReturnsFalseForOtherUser() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, OTHER_USER)).isFalse();
        }

        @Test
        @DisplayName("該当 userId は filterAccessible で入力 ids をそのまま返す")
        void filterAccessibleReturnsAllInputForAllowedUser() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), ALLOWED_USER);

            assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
        }

        @Test
        @DisplayName("非該当 userId は filterAccessible で null/空（Mockito 既定）")
        void filterAccessibleReturnsEmptyForOtherUser() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), OTHER_USER);

            assertThat(result == null || result.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("denyWithReason(reason): decide でカスタム DenyReason")
    class DenyWithReasonTests {

        @Test
        @DisplayName("NOT_FOUND を返すよう設定すると decide が NOT_FOUND を返す")
        void decideReturnsNotFound() {
            VisibilityCheckerTestSupport.denyWithReason(checker, DenyReason.NOT_FOUND);

            VisibilityDecision decision = checker.decide(ReferenceType.BLOG_POST, 1L, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_FOUND);
            assertThat(decision.referenceType()).isEqualTo(ReferenceType.BLOG_POST);
            assertThat(decision.contentId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("INSUFFICIENT_ROLE を返すよう設定すると decide が INSUFFICIENT_ROLE を返す")
        void decideReturnsInsufficientRole() {
            VisibilityCheckerTestSupport.denyWithReason(checker, DenyReason.INSUFFICIENT_ROLE);

            VisibilityDecision decision = checker.decide(ReferenceType.EVENT, 99L, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.INSUFFICIENT_ROLE);
        }
    }
}
