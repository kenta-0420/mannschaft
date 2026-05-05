package com.mannschaft.app.admin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ベータ登録制限設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BetaRestrictionConfigResponse {

    private final Boolean isEnabled;
    private final Long maxTeamId;
    private final Long maxOrgId;
    private final LocalDateTime updatedAt;
}
