package com.mannschaft.app.websocket;

import java.security.Principal;
import java.util.Objects;

/**
 * STOMP セッションに紐づける軽量 Principal（設計書 §2.3 / §4.1）。
 *
 * <p>{@link java.security.Principal#getName()} が userId 文字列を返す。
 * {@code WebSocketAuthChannelInterceptor} が CONNECT 時に JWT から得た userId で生成し
 * {@code accessor.setUser(...)} で確立することで、{@code SimpUserRegistry} に
 * ユーザー→セッションが登録され {@code convertAndSendToUser} が解決可能になる。</p>
 *
 * <p>これは値クラス（振る舞いなし）であり、Principal 未配線という欠陥（§2.3）は
 * 本クラスの有無ではなく {@code WebSocketAuthChannelInterceptor} が {@code setUser} を
 * 呼ぶか否かにある。配線本体は出陣隊（隊 1）が実装する。</p>
 */
public final class StompPrincipal implements Principal {

    private final String name;

    public StompPrincipal(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StompPrincipal that)) {
            return false;
        }
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }

    @Override
    public String toString() {
        return "StompPrincipal{name='" + name + "'}";
    }
}
