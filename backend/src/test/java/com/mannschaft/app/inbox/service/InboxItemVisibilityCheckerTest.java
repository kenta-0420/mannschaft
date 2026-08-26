package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link InboxItemVisibilityChecker} のユニットテスト（Phase3b E-3 / IDOR 防止）。
 *
 * <p>triage 書き込み前の本人宛て判定をアダプタへ委譲する協力オブジェクトの挙動を固定する。
 * <b>現状の実装挙動の固定</b>が目的（仕様変更しない）。
 *
 * <p>観点:
 * <ul>
 *   <li>本人宛て（アダプタ true）→ 可視 true</li>
 *   <li>他人宛て（アダプタ false）→ 不可視 false（triage 拒否）</li>
 *   <li>担当アダプタ未登録の sourceType → 不可視 false（fail-closed）</li>
 * </ul>
 */
@DisplayName("InboxItemVisibilityChecker IDOR 防止")
class InboxItemVisibilityCheckerTest {

    private static final Long USER_ID = 1L;
    private static final Long SOURCE_ID = 555L;

    /** 指定 sourceType を担当し、isVisibleTo を固定値で返すスタブアダプタ。 */
    private InboxSourceAdapter stubAdapter(InboxSourceType type, boolean visible) {
        InboxSourceAdapter adapter = mock(InboxSourceAdapter.class);
        when(adapter.sourceType()).thenReturn(type);
        when(adapter.isVisibleTo(USER_ID, SOURCE_ID)).thenReturn(visible);
        return adapter;
    }

    @Test
    @DisplayName("本人宛て（アダプタ true）→ 可視 true")
    void selfOwned_visible() {
        InboxItemVisibilityChecker checker = new InboxItemVisibilityChecker(
                List.of(stubAdapter(InboxSourceType.TODO_DUE, true)));

        assertThat(checker.isVisibleTo(USER_ID, InboxSourceType.TODO_DUE, SOURCE_ID)).isTrue();
    }

    @Test
    @DisplayName("他人宛て（アダプタ false）→ 不可視 false（triage 拒否）")
    void otherOwned_notVisible() {
        InboxItemVisibilityChecker checker = new InboxItemVisibilityChecker(
                List.of(stubAdapter(InboxSourceType.TODO_DUE, false)));

        assertThat(checker.isVisibleTo(USER_ID, InboxSourceType.TODO_DUE, SOURCE_ID)).isFalse();
    }

    @Test
    @DisplayName("担当アダプタが未登録の sourceType → 不可視 false（fail-closed）")
    void noAdapterForType_failClosed() {
        // TODO_DUE のアダプタのみ登録 → MENTION への問い合わせは false
        InboxItemVisibilityChecker checker = new InboxItemVisibilityChecker(
                List.of(stubAdapter(InboxSourceType.TODO_DUE, true)));

        assertThat(checker.isVisibleTo(USER_ID, InboxSourceType.MENTION, SOURCE_ID)).isFalse();
    }

    @Test
    @DisplayName("アダプタが全く無い場合もどの sourceType も false（fail-closed）")
    void emptyAdapters_failClosed() {
        InboxItemVisibilityChecker checker = new InboxItemVisibilityChecker(List.of());

        for (InboxSourceType type : InboxSourceType.values()) {
            assertThat(checker.isVisibleTo(USER_ID, type, SOURCE_ID))
                    .as("sourceType=%s", type)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("sourceType ごとに対応するアダプタへ正しくルーティングする")
    void routesToCorrectAdapterByType() {
        // NOTIFICATION は可視 / TODO_DUE は不可視のアダプタを併存させる
        InboxItemVisibilityChecker checker = new InboxItemVisibilityChecker(List.of(
                stubAdapter(InboxSourceType.NOTIFICATION, true),
                stubAdapter(InboxSourceType.TODO_DUE, false)));

        assertThat(checker.isVisibleTo(USER_ID, InboxSourceType.NOTIFICATION, SOURCE_ID)).isTrue();
        assertThat(checker.isVisibleTo(USER_ID, InboxSourceType.TODO_DUE, SOURCE_ID)).isFalse();
    }
}
