package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import java.time.LocalDateTime;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class AuthMapperImpl implements AuthMapper {

    @Override
    public UserProfileResponse toUserProfileResponse(UserEntity user) {
        if ( user == null ) {
            return null;
        }

        Long id = null;
        String email = null;
        String lastName = null;
        String firstName = null;
        String lastNameKana = null;
        String firstNameKana = null;
        String displayName = null;
        String nickname2 = null;
        Boolean isSearchable = null;
        String avatarUrl = null;
        String phoneNumber = null;
        String locale = null;
        String timezone = null;
        LocalDateTime lastLoginAt = null;
        LocalDateTime createdAt = null;

        id = user.getId();
        email = user.getEmail();
        lastName = user.getLastName();
        firstName = user.getFirstName();
        lastNameKana = user.getLastNameKana();
        firstNameKana = user.getFirstNameKana();
        displayName = user.getDisplayName();
        nickname2 = user.getNickname2();
        isSearchable = user.getIsSearchable();
        avatarUrl = user.getAvatarUrl();
        phoneNumber = user.getPhoneNumber();
        locale = user.getLocale();
        timezone = user.getTimezone();
        lastLoginAt = user.getLastLoginAt();
        createdAt = user.getCreatedAt();

        boolean hasPassword = false;
        boolean is2faEnabled = false;
        int webauthnCount = 0;
        List<String> oauthProviders = null;
        String status = user.getStatus() != null ? user.getStatus().name() : null;

        UserProfileResponse userProfileResponse = new UserProfileResponse( id, email, lastName, firstName, lastNameKana, firstNameKana, displayName, nickname2, isSearchable, avatarUrl, phoneNumber, locale, timezone, status, hasPassword, is2faEnabled, webauthnCount, oauthProviders, lastLoginAt, createdAt );

        return userProfileResponse;
    }

    @Override
    public SessionResponse toSessionResponse(RefreshTokenEntity refreshToken) {
        if ( refreshToken == null ) {
            return null;
        }

        Long id = null;
        String ipAddress = null;
        String userAgent = null;
        boolean rememberMe = false;
        LocalDateTime createdAt = null;
        LocalDateTime lastUsedAt = null;

        id = refreshToken.getId();
        ipAddress = refreshToken.getIpAddress();
        userAgent = refreshToken.getUserAgent();
        if ( refreshToken.getRememberMe() != null ) {
            rememberMe = refreshToken.getRememberMe();
        }
        createdAt = refreshToken.getCreatedAt();
        lastUsedAt = refreshToken.getLastUsedAt();

        boolean isCurrent = false;

        SessionResponse sessionResponse = new SessionResponse( id, ipAddress, userAgent, rememberMe, createdAt, lastUsedAt, isCurrent );

        return sessionResponse;
    }

    @Override
    public WebAuthnCredentialResponse toWebAuthnCredentialResponse(WebAuthnCredentialEntity credential) {
        if ( credential == null ) {
            return null;
        }

        Long id = null;
        String credentialId = null;
        String deviceName = null;
        String aaguid = null;
        LocalDateTime lastUsedAt = null;
        LocalDateTime createdAt = null;

        id = credential.getId();
        credentialId = credential.getCredentialId();
        deviceName = credential.getDeviceName();
        aaguid = credential.getAaguid();
        lastUsedAt = credential.getLastUsedAt();
        createdAt = credential.getCreatedAt();

        WebAuthnCredentialResponse webAuthnCredentialResponse = new WebAuthnCredentialResponse( id, credentialId, deviceName, aaguid, lastUsedAt, createdAt );

        return webAuthnCredentialResponse;
    }

    @Override
    public OAuthProviderResponse toOAuthProviderResponse(OAuthAccountEntity oauthAccount) {
        if ( oauthAccount == null ) {
            return null;
        }

        LocalDateTime connectedAt = null;
        String providerEmail = null;

        connectedAt = oauthAccount.getCreatedAt();
        providerEmail = oauthAccount.getProviderEmail();

        String provider = oauthAccount.getProvider().name();

        OAuthProviderResponse oAuthProviderResponse = new OAuthProviderResponse( provider, providerEmail, connectedAt );

        return oAuthProviderResponse;
    }

    @Override
    public LoginHistoryResponse toLoginHistoryResponse(AuditLogEntity auditLog) {
        if ( auditLog == null ) {
            return null;
        }

        Long id = null;
        String ipAddress = null;
        String userAgent = null;
        LocalDateTime createdAt = null;

        id = auditLog.getId();
        ipAddress = auditLog.getIpAddress();
        userAgent = auditLog.getUserAgent();
        createdAt = auditLog.getCreatedAt();

        String method = null;
        String eventType = auditLog.getEventType() != null ? auditLog.getEventType().name() : null;

        LoginHistoryResponse loginHistoryResponse = new LoginHistoryResponse( id, eventType, ipAddress, userAgent, method, createdAt );

        return loginHistoryResponse;
    }
}
