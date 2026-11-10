package com.mannschaft.app.config.websocket;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration;

/** 全既存Configurerを適用し、gateと終端close Handlerを単一broker構成へ組み込む。 */
@Configuration
public class TerminalCloseWebSocketBrokerConfig extends DelegatingWebSocketMessageBrokerConfiguration {
    private final ObjectProvider<TaskScheduler> scheduler;

    public TerminalCloseWebSocketBrokerConfig(
            @Qualifier("messageBrokerTaskScheduler") ObjectProvider<TaskScheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @Bean
    public FatalStompSessionGate fatalStompSessionGate() {
        return new FatalStompSessionGate();
    }

    @Override
    protected void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(fatalStompSessionGate());
        super.configureClientInboundChannel(registration);
    }

    @Override
    @Bean
    public WebSocketHandler subProtocolWebSocketHandler(
            @Qualifier("clientInboundChannel") AbstractSubscribableChannel clientInboundChannel,
            @Qualifier("clientOutboundChannel") AbstractSubscribableChannel clientOutboundChannel) {
        TerminalCloseSubProtocolWebSocketHandler handler = new TerminalCloseSubProtocolWebSocketHandler(
                clientInboundChannel, clientOutboundChannel, scheduler::getObject);
        handler.setPhase(getPhase());
        return handler;
    }
}
