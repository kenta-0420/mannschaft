package com.mannschaft.app.proxy.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** 保存済みの代理入力操作記録。後見切替のconsentIdはnullを保持する。 */
@Getter
@Builder
public class ProxyInputRecordResponse {
    private Long id;
    private Long consentId;
    private Long subjectUserId;
    private Long proxyUserId;
    private String featureScope;
    private String targetEntityType;
    private Long targetEntityId;
    private String inputSource;
    private String originalStorageLocation;
    private Long auditLogId;
    private LocalDateTime createdAt;
}
