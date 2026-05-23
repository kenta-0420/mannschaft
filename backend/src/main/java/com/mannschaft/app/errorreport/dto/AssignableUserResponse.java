package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F10.6 Phase 10-δ — 担当者候補ユーザー情報（assignable-users API レスポンス）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignableUserResponse {
    /** ユーザーID。 */
    private Long id;
    /** 表示名。 */
    private String displayName;
    /** プロフィール画像URL（null 可）。 */
    private String profileImageUrl;
}
