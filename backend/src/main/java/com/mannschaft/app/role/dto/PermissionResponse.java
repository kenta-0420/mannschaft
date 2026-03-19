package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パーミッションレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class PermissionResponse {

    private final Long id;
    private final String name;
    private final String displayName;
    private final String scope;
}
