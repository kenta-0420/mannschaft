package com.mannschaft.app.websocket.relay;

/**
 * 中継メッセージの封筒（設計書 §4.5.1 確定 JSON スキーマ）。
 *
 * <p>キー名は camelCase 固定。フィールドは §4.5.1 の 6 項目のみ（前方互換のため
 * 受信側 {@code ObjectMapper} は {@code FAIL_ON_UNKNOWN_PROPERTIES=false} で運用する）。</p>
 *
 * <p>これは純粋なデータ封筒（DTO）であり、シリアライズスキーマそのものが設計成果物である。
 * 中継ロジック（捕捉・publish・再注入・ループ防止）は {@link WebSocketRelayPublisher} /
 * {@link WebSocketRelaySubscriber} が担い、出陣隊が実装する。</p>
 *
 * <table>
 *   <caption>フィールド（§4.5.1）</caption>
 *   <tr><th>キー</th><th>型</th><th>内容</th></tr>
 *   <tr><td>originNodeId</td><td>String(UUID)</td><td>発信ノード ID（ループ防止）</td></tr>
 *   <tr><td>messageType</td><td>String</td><td>{@code BROADCAST} / {@code USER}</td></tr>
 *   <tr><td>destination</td><td>String</td><td>BROADCAST は {@code /topic/...}、USER はサブ destination</td></tr>
 *   <tr><td>userId</td><td>String/null</td><td>USER のみ。宛先ユーザー ID</td></tr>
 *   <tr><td>contentType</td><td>String</td><td>ペイロード MIME</td></tr>
 *   <tr><td>body</td><td>String(Base64)</td><td>ペイロードのバイト列</td></tr>
 * </table>
 */
public class RelayEnvelope {

    private String originNodeId;
    private String messageType;
    private String destination;
    private String userId;
    private String contentType;
    private String body;

    public RelayEnvelope() {
    }

    public RelayEnvelope(String originNodeId, String messageType, String destination,
                         String userId, String contentType, String body) {
        this.originNodeId = originNodeId;
        this.messageType = messageType;
        this.destination = destination;
        this.userId = userId;
        this.contentType = contentType;
        this.body = body;
    }

    public String getOriginNodeId() {
        return originNodeId;
    }

    public void setOriginNodeId(String originNodeId) {
        this.originNodeId = originNodeId;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
