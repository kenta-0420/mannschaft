package com.mannschaft.app.common.websocket;

import org.springframework.messaging.MessagingException;

import java.util.Objects;

/**
 * STOMP の CONNECT / SUBSCRIBE を拒否するための例外。拒否理由を {@link StompErrorCode} で保持する。
 *
 * <p>inbound チャンネルのインターセプタから送出すると、Spring の {@code StompSubProtocolHandler} が
 * ERROR フレームを返して接続を閉じる。その際 {@link StompErrorCodeErrorHandler} が
 * {@link StompErrorCode#HEADER} ヘッダへコードを載せる。{@link #getMessage()} は従来どおり
 * ERROR フレームの {@code message} ヘッダになる。</p>
 *
 * <p>{@link MessagingException} の派生のため、従来の {@code MessagingException} 前提の呼び出し側・テストとも互換。</p>
 */
public class StompRejectedException extends MessagingException {

    private final StompErrorCode errorCode;

    /**
     * @param errorCode   機械判読用の拒否理由コード
     * @param description ERROR フレームの {@code message} ヘッダに載る文言
     */
    public StompRejectedException(StompErrorCode errorCode, String description) {
        super(description);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 拒否理由コードを返す。 */
    public StompErrorCode getErrorCode() {
        return errorCode;
    }
}
