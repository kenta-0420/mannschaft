package com.mannschaft.app.digest.event;

import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * AI ダイジェスト非同期生成の起動リスナーの単体テスト（Issue #2990 L3）。
 *
 * <p>是正の本体は「起動位置を業務TXの内側から {@code AFTER_COMMIT} の後へ移したこと」である。
 * そこで本テストは (1) 起動が {@code AFTER_COMMIT} 境界に紐付いていること（注釈の実測）、
 * (2) イベントの内容がそのまま executor へ渡ること、(3) 起動失敗を握りつぶさないこと、を固定する。</p>
 *
 * <p>「業務TX 内から直接 executor を呼んでいないこと」自体は
 * {@code NotificationTransactionBoundaryGuardTest}（凍結台帳から
 * {@code DigestGenerationService#generate} / {@code #regenerate} を削除した）が機械的に固定している。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DigestAiGenerationDispatchListener 単体テスト")
class DigestAiGenerationDispatchListenerTest {

    @Mock private DigestAsyncExecutor digestAsyncExecutor;

    @InjectMocks private DigestAiGenerationDispatchListener listener;

    @Test
    @DisplayName("起動は AFTER_COMMIT 境界に紐付いている（@Async 単独では因果順序を保証しないため）")
    void listensAfterCommit() throws NoSuchMethodException {
        Method m = DigestAiGenerationDispatchListener.class.getMethod(
                "onDigestAiGenerationRequested", DigestAiGenerationRequestedEvent.class);
        TransactionalEventListener annotation = m.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation)
                .as("業務TXの commit 後に起動する必要がある（未 commit だと非同期側の findById が行を見つけられず"
                        + "「生成失敗」の嘘の通知が飛ぶ）")
                .isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("イベントの内容をそのまま非同期 executor へ渡す")
    void delegatesEventPayloadToExecutor() {
        DigestAiGenerationRequestedEvent event = new DigestAiGenerationRequestedEvent(
                77L, "TEAM", 42L, "SUMMARY", "追加プロンプト", true, false, true, "ja");

        listener.onDigestAiGenerationRequested(event);

        verify(digestAsyncExecutor).generateAiDigestAsync(
                77L, "TEAM", 42L, "SUMMARY", "追加プロンプト", true, false, true, "ja");
    }

    @Test
    @DisplayName("起動失敗は呼び出し元へ伝播させない（AFTER_COMMIT で投げても拾い手がいないため ERROR ログに残す）")
    void doesNotPropagateDispatchFailure() {
        DigestAiGenerationRequestedEvent event = new DigestAiGenerationRequestedEvent(
                78L, "ORGANIZATION", 43L, "SUMMARY", null, true, true, false, "ja");
        willThrow(new IllegalStateException("task rejected"))
                .given(digestAsyncExecutor).generateAiDigestAsync(
                        78L, "ORGANIZATION", 43L, "SUMMARY", null, true, true, false, "ja");

        // 例外が漏れないこと（ダイジェストは GENERATING のまま残り、既存のタイムアウト回収が拾う）
        listener.onDigestAiGenerationRequested(event);

        verify(digestAsyncExecutor).generateAiDigestAsync(
                78L, "ORGANIZATION", 43L, "SUMMARY", null, true, true, false, "ja");
    }
}
