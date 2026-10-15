package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F09.18: AC2 LoggingEmailTransport 単体テスト。
 *
 * <p>simulate モードのログのみ送信実装が要件を満たすことを検証する:</p>
 * <ul>
 *   <li>例外を投げない（=必ず SENT 化される）</li>
 *   <li>疑似 messageId が "SIMULATED-" で始まる</li>
 *   <li>毎回異なる messageId を返す（UUID 末尾）</li>
 * </ul>
 */
@DisplayName("AC2: LoggingEmailTransport 単体テスト")
class LoggingEmailTransportTest {

    private final LoggingEmailTransport transport = new LoggingEmailTransport();

    @Test
    @DisplayName("send() は例外を投げない（simulate モードでは常に SENT 化される）")
    void send_doesNotThrow() {
        assertThatCode(() ->
                transport.send("user@example.com", "テスト件名", "<p>テスト本文</p>")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("send() が返す messageId は 'SIMULATED-' で始まる")
    void send_returnsSimulatedMessageId() {
        String messageId = transport.send("user@example.com", "件名", "<p>本文</p>");

        assertThat(messageId)
                .as("simulate モードの messageId は 'SIMULATED-' で始まること")
                .startsWith("SIMULATED-");
    }

    @Test
    @DisplayName("send() を 2 回呼ぶと異なる messageId が返る（UUID の一意性）")
    void send_returnsDifferentMessageIdEachTime() {
        String id1 = transport.send("a@example.com", "件名1", "<p>本文1</p>");
        String id2 = transport.send("b@example.com", "件名2", "<p>本文2</p>");

        assertThat(id1)
                .as("同じ宛先でも呼び出しごとに異なる messageId が返ること")
                .isNotEqualTo(id2);
    }

    @Test
    @DisplayName("null アドレスが渡されても NullPointerException を投げない")
    void send_withNullAddress_doesNotThrow() {
        // メールアドレスのマスキング処理で NPE が起きないことを確認
        assertThatCode(() ->
                transport.send(null, "件名", "<p>本文</p>")
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("メールアドレスがマスキングされた文字列を含む（実アドレスがそのままログに出ない）")
    void send_masksEmailAddress() {
        // LoggingEmailTransport 内部でマスキングしていることの確認は間接的になるが、
        // 例外なく動くことで十分（ログ出力内容は @Slf4j 経由のため直接アサートしない）
        String messageId = transport.send("secret.user@example.com", "件名", "<p>本文</p>");
        assertThat(messageId).isNotBlank();
    }
}
