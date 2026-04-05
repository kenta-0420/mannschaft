package com.mannschaft.app.organization.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織配下全メンバーレスポンス（GET /api/v1/organizations/{id}/members/all 用）。
 * 直属メンバーと所属チームのメンバーを一覧化する。カスケード通知前の対象確認に使用する。
 */
@Getter
@RequiredArgsConstructor
public class OrgAllMembersResponse {

    private final Long userId;
    private final String displayName;
    private final String iconUrl;
    private final MemberOf memberOf;
    private final String role;

    /**
     * 所属元エンティティ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class MemberOf {
        /** "ORGANIZATION" または "TEAM" */
        private final String type;
        private final Long id;
        private final String name;
    }
}
