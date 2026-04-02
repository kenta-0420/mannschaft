package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 認証ドメインのエンティティ⇔DTOマッピング定義。
 * Service側で手動設定が必要なフィールドは ignore で明示し、Service層が責任を持つ。
 */
@Mapper(componentModel = "spring")
public interface AuthMapper {

    // ========================================
    // UserEntity → UserProfileResponse
    // ========================================

    /**
     * UserEntity から UserProfileResponse への部分マッピング。
     * hasPassword, is2faEnabled, webauthnCount, oauthProviders は
     * Service 側で手動設定するため ignore とする。
     */
    @Mapping(target = "hasPassword", ignore = true)
    @Mapping(target = "is2faEnabled", ignore = true)
    @Mapping(target = "webauthnCount", ignore = true)
    @Mapping(target = "oauthProviders", ignore = true)
    @Mapping(target = "status", expression = "java(user.getStatus() != null ? user.getStatus().name() : null)")
    UserProfileResponse toUserProfileResponse(UserEntity user);

    // ========================================
    // RefreshTokenEntity → SessionResponse
    // ========================================

    /**
     * RefreshTokenEntity から SessionResponse へのマッピング。
     * isCurrent は Service 側でリクエスト中のトークンと比較して判定するため ignore とする。
     */
    @Mapping(target = "deviceName", ignore = true)
    @Mapping(target = "deviceType", ignore = true)
    @Mapping(target = "isCurrent", ignore = true)
    SessionResponse toSessionResponse(RefreshTokenEntity refreshToken);

    // ========================================
    // WebAuthnCredentialEntity → WebAuthnCredentialResponse
    // ========================================

    /**
     * WebAuthnCredentialEntity から WebAuthnCredentialResponse へのマッピング。
     */
    WebAuthnCredentialResponse toWebAuthnCredentialResponse(WebAuthnCredentialEntity credential);

    // ========================================
    // OAuthAccountEntity → OAuthProviderResponse
    // ========================================

    /**
     * OAuthAccountEntity から OAuthProviderResponse へのマッピング。
     * provider は enum から String へ変換する。connectedAt は createdAt をマッピングする。
     */
    @Mapping(target = "provider", expression = "java(oauthAccount.getProvider().name())")
    @Mapping(source = "createdAt", target = "connectedAt")
    OAuthProviderResponse toOAuthProviderResponse(OAuthAccountEntity oauthAccount);

    // ========================================
    // AuditLogEntity → LoginHistoryResponse
    // ========================================

    /**
     * AuditLogEntity から LoginHistoryResponse へのマッピング。
     * method フィールドは Service 側で eventType から判定して設定するため ignore とする。
     * eventType は enum から String へ変換する。
     */
    @Mapping(target = "method", ignore = true)
    LoginHistoryResponse toLoginHistoryResponse(AuditLogEntity auditLog);
}
