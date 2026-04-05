package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通知既読リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class MarkReadRequest {

    private final Boolean isRead;
}
