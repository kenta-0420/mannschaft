package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ロール呼称レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RoleAliasResponse {

    private final String roleName;
    private final String displayAlias;
}
