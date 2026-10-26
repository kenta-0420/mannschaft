package com.mannschaft.app.common.visibility;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Resolver 同士の再帰呼び出し深度を追跡するカウンタ。
 *
 * <p>コンテナ型 Resolver (例: corkboard カードが BlogPost / Event を参照) が他 type の
 * 判定を呼ぶ際、循環参照や評価ループを防ぐため最大深度 {@value #MAX_DEPTH} を強制する。
 * 超過時は {@link IllegalStateException} を投げて安全側に倒す。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §15 D-16 完全一致。
 *
 * <p>{@link RequestScope} のため、リクエスト終了時に状態は破棄される。
 * 1 リクエスト内で複数回 {@link #enter()}/{@link #exit()} が呼ばれても、
 * 最終的に同数だけ呼び出されることが期待される (Resolver 実装の責務)。
 *
 * <p>使用例:
 * <pre>{@code
 * counter.enter();
 * try {
 *     return checker.canView(...);
 * } finally {
 *     counter.exit();
 * }
 * }</pre>
 */
@Component
@RequestScope
public class RecursionDepthCounter {

    /** 許容される最大ネスト深度 (この値を超える {@link #enter()} は失敗). */
    public static final int MAX_DEPTH = 3;

    private int depth = 0;

    /**
     * 再帰深度を 1 段増やす。
     *
     * @throws IllegalStateException 増加後の深度が {@link #MAX_DEPTH} を超える場合
     */
    public void enter() {
        if (depth >= MAX_DEPTH) {
            throw new IllegalStateException(
                "ContentVisibilityResolver recursion depth exceeded: max=" + MAX_DEPTH
                    + ", attempted=" + (depth + 1));
        }
        depth++;
    }

    /**
     * 再帰深度を 1 段減らす。
     *
     * <p>0 以下のときに呼ばれてもアンダーフローしない (防御的に 0 で固定)。
     * これにより finally 句でのガードが over-call になっても安全。
     */
    public void exit() {
        if (depth > 0) {
            depth--;
        }
    }

    /**
     * 現在の深度を返す。テスト・デバッグ用。
     *
     * @return 現在の深度 (0 〜 {@link #MAX_DEPTH})
     */
    public int currentDepth() {
        return depth;
    }
}
