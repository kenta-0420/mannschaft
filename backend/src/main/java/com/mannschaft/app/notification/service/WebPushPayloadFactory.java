package com.mannschaft.app.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Web Push ペイロード JSON を組み立てる（骨格。試練の red 確認用）。
 */
@Component
public class WebPushPayloadFactory {

    private final ObjectMapper objectMapper;
    private final String appBaseUrl;

    public WebPushPayloadFactory(ObjectMapper objectMapper, @Value("${app.base-url}") String appBaseUrl) {
        this.objectMapper = objectMapper;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * 通知から Web Push ペイロード JSON を生成する。
     *
     * @param notification 通知
     * @return JSON 文字列
     */
    public String build(NotificationEntity notification) {
        return "{}";
    }
}
