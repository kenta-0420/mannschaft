package com.mannschaft.app.common.visibility.testsupport;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.DenyReason;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityDecision;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * テストで {@link ContentVisibilityChecker} を mock するときの共通スタブ集。
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §13.8 完全一致。
 * {@code TEST_CONVENTION.md} にて、{@link ContentVisibilityChecker} を
 * {@code @MockBean} / {@code @Mock} するテストは本ユーティリティ経由でスタブする
 * ことが必須化されている。</p>
 *
 * <p>本基盤の API 進化（例: tenantId 追加 §17.Q14）に対する既存テストの脆弱性を
 * 低減する。直接 {@code when(checker.canView(...))} を書くと、API 変更時に数百
 * テストが一斉に壊れるが、本ユーティリティ経由なら本クラス 1 箇所の修正で
 * 全機能テストが追従する。</p>
 *
 * <p>提供する 5 メソッド:
 * <ul>
 *   <li>{@link #allowAll(ContentVisibilityChecker)}</li>
 *   <li>{@link #denyAll(ContentVisibilityChecker)}</li>
 *   <li>{@link #allowFor(ContentVisibilityChecker, ReferenceType, Set)}</li>
 *   <li>{@link #allowForUser(ContentVisibilityChecker, Long)}</li>
 *   <li>{@link #denyWithReason(ContentVisibilityChecker, DenyReason)}</li>
 * </ul>
 */
public final class VisibilityCheckerTestSupport {

    private VisibilityCheckerTestSupport() {
        throw new AssertionError("utility class");
    }

    /**
     * 全 type / 全 contentId / 全 userId に対して allow（最も多いユースケース）。
     *
     * <ul>
     *   <li>{@code canView} → 常に true</li>
     *   <li>{@code filterAccessible} → 入力 ids をそのまま Set に詰めて返す</li>
     *   <li>{@code filterAccessibleByType} → 入力 Map をそのまま Set 化して返す</li>
     *   <li>{@code decide} → {@link VisibilityDecision#allow}</li>
     *   <li>{@code assertCanView} → 何もしない（doNothing がデフォルト）</li>
     * </ul>
     *
     * @param checker {@code @MockBean} / {@code @Mock} 化された Checker
     */
    @SuppressWarnings("unchecked")
    public static void allowAll(ContentVisibilityChecker checker) {
        when(checker.canView(any(), any(), any())).thenReturn(true);
        when(checker.filterAccessible(any(), anyCollection(), any()))
            .thenAnswer(inv -> Set.copyOf((Collection<Long>) inv.getArgument(1)));
        when(checker.filterAccessibleByType(anyMap(), any()))
            .thenAnswer(inv -> {
                Map<ReferenceType, ? extends Collection<Long>> input = inv.getArgument(0);
                return input.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
            });
        when(checker.decide(any(), any(), any()))
            .thenAnswer(inv -> VisibilityDecision.allow(
                inv.getArgument(0), inv.getArgument(1)));
        // assertCanView は void なので doNothing がデフォルト挙動。明示不要。
    }

    /**
     * 全 deny。
     *
     * <ul>
     *   <li>{@code canView} → 常に false</li>
     *   <li>{@code filterAccessible} → 常に空 Set</li>
     *   <li>{@code filterAccessibleByType} → 入力 Map のキーをすべて空 Set にマップ</li>
     *   <li>{@code decide} → {@link VisibilityDecision#deny} (UNSPECIFIED)</li>
     *   <li>{@code assertCanView} → {@link RuntimeException} をスロー</li>
     * </ul>
     *
     * @param checker {@code @MockBean} / {@code @Mock} 化された Checker
     */
    public static void denyAll(ContentVisibilityChecker checker) {
        when(checker.canView(any(), any(), any())).thenReturn(false);
        when(checker.filterAccessible(any(), anyCollection(), any()))
            .thenReturn(Set.of());
        when(checker.filterAccessibleByType(anyMap(), any()))
            .thenAnswer(inv -> {
                Map<ReferenceType, ? extends Collection<Long>> input = inv.getArgument(0);
                return input.keySet().stream()
                    .collect(Collectors.toMap(k -> k, k -> Set.<Long>of()));
            });
        when(checker.decide(any(), any(), any()))
            .thenAnswer(inv -> VisibilityDecision.deny(
                inv.getArgument(0), inv.getArgument(1), DenyReason.UNSPECIFIED));
        doThrow(new RuntimeException("deny"))
            .when(checker).assertCanView(any(), any(), any());
    }

    /**
     * 特定 type の特定 contentId のみ allow。それ以外（type 不一致 or id 非該当）は
     * 既定の Mockito 挙動（false / 空 Set）となる。
     *
     * <p>本メソッドは {@code canView} と {@code filterAccessible} のみを上書きする。
     * {@code decide} / {@code filterAccessibleByType} / {@code assertCanView} の
     * スタブが必要な場合は、{@link #allowAll} や {@link #denyAll} と組み合わせるか、
     * 個別に追加 {@code when()} すること。</p>
     *
     * @param checker {@code @MockBean} / {@code @Mock} 化された Checker
     * @param type    allow 対象の {@link ReferenceType}
     * @param ids     allow 対象の contentId 集合
     */
    public static void allowFor(ContentVisibilityChecker checker,
                                 ReferenceType type, Set<Long> ids) {
        when(checker.canView(eq(type), any(), any()))
            .thenAnswer(inv -> ids.contains((Long) inv.getArgument(1)));
        when(checker.filterAccessible(eq(type), anyCollection(), any()))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Collection<Long> input = (Collection<Long>) inv.getArgument(1);
                return input.stream().filter(ids::contains).collect(Collectors.toSet());
            });
    }

    /**
     * 特定 userId についてのみ allow（他 userId は既定の Mockito 挙動 = false / 空 Set）。
     *
     * <p>{@code canView} は {@code userId} 一致時に true、{@code filterAccessible} は
     * 入力 ids をそのまま返す。</p>
     *
     * @param checker {@code @MockBean} / {@code @Mock} 化された Checker
     * @param userId  allow 対象の userId
     */
    @SuppressWarnings("unchecked")
    public static void allowForUser(ContentVisibilityChecker checker, Long userId) {
        when(checker.canView(any(), any(), eq(userId))).thenReturn(true);
        when(checker.filterAccessible(any(), anyCollection(), eq(userId)))
            .thenAnswer(inv -> Set.copyOf((Collection<Long>) inv.getArgument(1)));
    }

    /**
     * {@code decide()} でカスタム {@link DenyReason} を返す。
     *
     * <p>{@link ContentVisibilityChecker#assertCanView} の HTTP マッピング
     * （NOT_FOUND → 404 / その他 → 403）を検証する場合に使用する。</p>
     *
     * @param checker {@code @MockBean} / {@code @Mock} 化された Checker
     * @param reason  返したい {@link DenyReason}
     */
    public static void denyWithReason(ContentVisibilityChecker checker, DenyReason reason) {
        when(checker.decide(any(), any(), any()))
            .thenAnswer(inv -> VisibilityDecision.deny(
                inv.getArgument(0), inv.getArgument(1), reason));
    }
}
