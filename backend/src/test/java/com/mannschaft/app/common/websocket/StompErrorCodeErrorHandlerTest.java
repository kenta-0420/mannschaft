package com.mannschaft.app.common.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261104-0337 AC 0337-4: {@link StompErrorCodeErrorHandler} が ERROR フレームの
 * {@code x-error-code} ヘッダへ拒否理由コードを載せ、既存の {@code message} ヘッダを維持することの単体テスト。
 */
@DisplayName("StompErrorCodeErrorHandler ERROR フレーム x-error-code UT（CMP-261104-0337）")
class StompErrorCodeErrorHandlerTest {

    private final StompErrorCodeErrorHandler handler = new StompErrorCodeErrorHandler();

    private static Message<byte[]> clientSubscribe() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/channels/1");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static StompHeaderAccessor errorAccessor(Message<byte[]> error) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(error, StompHeaderAccessor.class);
        return accessor != null ? accessor : StompHeaderAccessor.wrap(error);
    }

    @Test
    @DisplayName("StompRejectedException(FORBIDDEN) → x-error-code=FORBIDDEN かつ message ヘッダは従来の文言")
    void FORBIDDENを載せる() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientSubscribe(), new StompRejectedException(StompErrorCode.FORBIDDEN, "チャネルの購読権限がありません"));

        StompHeaderAccessor accessor = errorAccessor(error);
        assertThat(accessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getFirstNativeHeader(StompErrorCode.HEADER)).isEqualTo("FORBIDDEN");
        assertThat(accessor.getMessage()).isEqualTo("チャネルの購読権限がありません");
    }

    @Test
    @DisplayName("StompRejectedException(UNAUTHENTICATED) → x-error-code=UNAUTHENTICATED")
    void UNAUTHENTICATEDを載せる() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientSubscribe(), new StompRejectedException(StompErrorCode.UNAUTHENTICATED, "未認証"));

        assertThat(errorAccessor(error).getFirstNativeHeader(StompErrorCode.HEADER)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("原因連鎖の奥にある StompRejectedException(INVALID_DESTINATION) も拾う")
    void 原因連鎖からも拾う() {
        MessageDeliveryException wrapped = new MessageDeliveryException(clientSubscribe(), "wrapped",
                new StompRejectedException(StompErrorCode.INVALID_DESTINATION, "宛先が不正です"));

        Message<byte[]> error = handler.handleClientMessageProcessingError(clientSubscribe(), wrapped);

        assertThat(errorAccessor(error).getFirstNativeHeader(StompErrorCode.HEADER)).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    @DisplayName("拒否例外以外（想定外の失敗）は x-error-code を付けない（FE は未知 ERROR として扱う）")
    void 想定外の失敗にはコードを付けない() {
        Message<byte[]> error = handler.handleClientMessageProcessingError(
                clientSubscribe(), new MessagingException("boom"));

        StompHeaderAccessor accessor = errorAccessor(error);
        assertThat(accessor.getFirstNativeHeader(StompErrorCode.HEADER)).isNull();
        assertThat(accessor.getMessage()).isEqualTo("boom");
    }
}
