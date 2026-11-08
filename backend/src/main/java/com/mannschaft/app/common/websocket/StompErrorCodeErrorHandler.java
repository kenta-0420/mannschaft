package com.mannschaft.app.common.websocket;

import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * STOMP の ERROR フレームに機械判読可能な拒否理由コードを載せる共通エラーハンドラ。
 *
 * <p>inbound チャンネルのインターセプタが {@link StompRejectedException} を送出したとき、
 * クライアントへ届く ERROR フレームのネイティブヘッダ {@value StompErrorCode#HEADER} に
 * {@link StompErrorCode#name()} を設定する。{@code message} ヘッダ（人間向け文言）は
 * 基底クラスの既定どおり例外メッセージのまま維持する。拒否例外以外の失敗にはコードを付けない
 * （クライアントは未知のエラーとして扱う）。</p>
 *
 * <p>{@code WebSocketConfig#registerStompEndpoints} で {@code StompEndpointRegistry#setErrorHandler} に登録する。
 * STOMP サブプロトコルハンドラは全エンドポイント（{@code /ws}・{@code /ws/signage}）で共有されるため、
 * 登録は 1 か所でよい。設計書: docs/architecture/websocket_external_broker_valkey.md §2.3.2</p>
 */
public class StompErrorCodeErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload,
                                             @Nullable Throwable cause,
                                             @Nullable StompHeaderAccessor clientHeaderAccessor) {
        StompRejectedException rejected = findRejection(cause);
        if (rejected != null) {
            errorHeaderAccessor.setNativeHeader(StompErrorCode.HEADER, rejected.getErrorCode().name());
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }

    /** 原因連鎖をたどって最初の {@link StompRejectedException} を返す（見つからなければ {@code null}）。 */
    @Nullable
    private static StompRejectedException findRejection(@Nullable Throwable cause) {
        Throwable current = cause;
        int depth = 0;
        while (current != null && depth < 16) {
            if (current instanceof StompRejectedException rejected) {
                return rejected;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
