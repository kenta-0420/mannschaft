package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecursionDepthCounter} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §15 D-16。
 *
 * <p>{@code MAX_DEPTH = 3} の境界、enter/exit の対称性、underflow 防御を検証する。
 */
@DisplayName("RecursionDepthCounter の深度管理")
class RecursionDepthCounterTest {

    private RecursionDepthCounter counter;

    @BeforeEach
    void setUp() {
        counter = new RecursionDepthCounter();
    }

    @Test
    @DisplayName("初期状態の深度は 0")
    void initialDepthIsZero() {
        assertThat(counter.currentDepth()).isZero();
    }

    @Test
    @DisplayName("enter で深度が 1 ずつ増える")
    void enter_incrementsDepth() {
        counter.enter();
        assertThat(counter.currentDepth()).isEqualTo(1);

        counter.enter();
        assertThat(counter.currentDepth()).isEqualTo(2);

        counter.enter();
        assertThat(counter.currentDepth()).isEqualTo(3);
    }

    @Test
    @DisplayName("exit で深度が 1 ずつ減る")
    void exit_decrementsDepth() {
        counter.enter();
        counter.enter();
        counter.enter();
        assertThat(counter.currentDepth()).isEqualTo(3);

        counter.exit();
        assertThat(counter.currentDepth()).isEqualTo(2);
        counter.exit();
        assertThat(counter.currentDepth()).isEqualTo(1);
        counter.exit();
        assertThat(counter.currentDepth()).isZero();
    }

    @Test
    @DisplayName("MAX_DEPTH (3) を超える enter は IllegalStateException")
    void enter_throwsWhenExceedingMaxDepth() {
        counter.enter();
        counter.enter();
        counter.enter();
        assertThat(counter.currentDepth())
            .isEqualTo(RecursionDepthCounter.MAX_DEPTH);

        assertThatThrownBy(() -> counter.enter())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recursion depth exceeded")
            .hasMessageContaining("max=3");
    }

    @Test
    @DisplayName("超過時に enter が失敗しても深度自体は MAX_DEPTH のまま (副作用なし)")
    void enter_failureDoesNotIncrementDepth() {
        counter.enter();
        counter.enter();
        counter.enter();

        assertThatThrownBy(() -> counter.enter())
            .isInstanceOf(IllegalStateException.class);

        assertThat(counter.currentDepth())
            .isEqualTo(RecursionDepthCounter.MAX_DEPTH);
    }

    @Test
    @DisplayName("0 の状態で exit を呼んでも underflow せず 0 のまま")
    void exit_doesNotUnderflow() {
        counter.exit();
        counter.exit();
        counter.exit();

        assertThat(counter.currentDepth()).isZero();
    }

    @Test
    @DisplayName("enter→exit を MAX_DEPTH 分繰り返しても深度は 0 に戻る")
    void enterExit_isSymmetric() {
        for (int i = 0; i < RecursionDepthCounter.MAX_DEPTH; i++) {
            counter.enter();
        }
        for (int i = 0; i < RecursionDepthCounter.MAX_DEPTH; i++) {
            counter.exit();
        }
        assertThat(counter.currentDepth()).isZero();

        // 再度 enter できる (1 リクエスト内で複数経路を経由するシナリオ)
        counter.enter();
        assertThat(counter.currentDepth()).isEqualTo(1);
    }
}
