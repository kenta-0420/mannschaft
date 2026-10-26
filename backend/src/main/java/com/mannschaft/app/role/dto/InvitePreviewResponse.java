package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 招待プレビューレスポンス。未認証ユーザーにも表示可能。
 */
@Getter
@RequiredArgsConstructor
public class InvitePreviewResponse {

    private final String targetName;
    private final String targetType;
    private final String roleName;
    private final boolean isValid;
}
