package com.mannschaft.app.common.websocket;

/**
 * STOMP の ERROR フレームでクライアントへ返す、機械判読可能な拒否理由コード。
 *
 * <p>{@link StompRejectedException} が保持し、{@link StompErrorCodeErrorHandler} が
 * ERROR フレームのネイティブヘッダ {@value #HEADER} に {@link #name()} を載せる。
 * 既存の {@code message} ヘッダ（人間向け文言）は従来どおり併記する。</p>
 *
 * <p>クライアント（FE）はこのコードで再接続方針を切り替える（設計書
 * {@code docs/architecture/websocket_external_broker_valkey.md} §2.3.1）。</p>
 * <ul>
 *   <li>{@link #UNAUTHENTICATED}: 認証が無い、または提示された資格情報が無効。トークン更新後の再接続で解消しうる。</li>
 *   <li>{@link #FORBIDDEN}: 認証済みだが当該宛先の購読権限が無い（不在・削除済みも同じコードで返し存在を漏らさない）。
 *       再接続しても解消しない。</li>
 *   <li>{@link #INVALID_DESTINATION}: 宛先の識別子が解釈できない。再接続しても解消しない。</li>
 * </ul>
 */
public enum StompErrorCode {

    /** 未認証（資格情報なし・無効）。 */
    UNAUTHENTICATED,

    /** 認証済みだが権限なし。 */
    FORBIDDEN,

    /** 宛先が不正。 */
    INVALID_DESTINATION;

    /** ERROR フレームで拒否理由コードを載せるネイティブヘッダ名。 */
    public static final String HEADER = "x-error-code";
}
