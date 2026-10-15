package com.mannschaft.app.membership.dto;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * メンバーシップ作成（入会）リクエスト DTO。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。
 * 設計書 §6.2 / §9.8 Mass Assignment 対策に従い、
 * サーバ管理項目（id / joinedAt / leftAt / leaveReason / gdprMaskedAt /
 * createdAt / updatedAt）はリクエストに含めても無視する設計のため、
 * エンティティと別の専用 DTO として定義する。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2 / §9.8</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipCreateRequest {

    /** 対象ユーザー ID。SUPPORTER 自己登録時は自分自身、ADMIN 直接付与時は他人を指定する。 */
    @NotNull
    private Long userId;

    @NotNull
    private ScopeType scopeType;

    @NotNull
    private Long scopeId;

    /** 既定 MEMBER。SUPPORTER 自己登録時のみ SUPPORTER。 */
    private RoleKind roleKind;

    /** 招待者 user_id。INVITE_TOKEN ルートでは必須。 */
    private Long invitedBy;

    /** 入会経路。INVITE_TOKEN / SUPPORTER_APPLICATION / SELF_SUPPORTER_REGISTRATION / ADMIN_DIRECT。 */
    private String source;
}
