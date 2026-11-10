package com.mannschaft.app.config.websocket;

import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration;

/**
 * CMP-261110-2011 配線試練用API骨格。現行Enableを切替えず、component登録しない。
 * supported Bean差替え・gate先行登録は正式red後に実装する。
 */
public class TerminalCloseWebSocketBrokerConfig extends DelegatingWebSocketMessageBrokerConfiguration {
}
