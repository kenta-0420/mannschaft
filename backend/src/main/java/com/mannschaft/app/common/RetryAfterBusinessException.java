package com.mannschaft.app.common;

/**
 * 「しばらく待てば通る」業務例外。{@link GlobalExceptionHandler} がエラーコード既定のステータス
 * （主に 429）に {@code Retry-After} ヘッダ（残り秒数）を付けて返す。
 *
 * <p>本文は通常の {@link BusinessException} と同じ {@link ErrorResponse}。残り秒数は本文に載せず
 * ヘッダだけで伝える（CMP-261012-0910 殿裁定: メールOTP 再送待ちの 429 は {@code Retry-After} で残り秒数を返す）。</p>
 */
public class RetryAfterBusinessException extends BusinessException {

    private final long retryAfterSeconds;

    /**
     * @param errorCode         エラーコード（ステータスは {@link GlobalExceptionHandler#resolveStatus} の既定に従う）
     * @param retryAfterSeconds 再試行までの残り秒数（1 未満は 1 に切り上げる）
     */
    public RetryAfterBusinessException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    /** {@code Retry-After} ヘッダへ載せる秒数（1 以上）。 */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
