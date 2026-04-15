package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ユーザープロフィールレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class UserProfileResponse {

    private final Long id;
    private final String email;
    private final String lastName;
    private final String firstName;
    private final String lastNameKana;
    private final String firstNameKana;
    private final String displayName;
    private final String nickname2;
    private final Boolean isSearchable;
    private final String avatarUrl;
    private final String phoneNumber;
    private final String locale;
    /** ISO 3166-1 alpha-2 国コード。NULLの場合はlocaleから推定する。 */
    private final String countryCode;
    private final String timezone;
    private final String status;
    private final boolean hasPassword;
    private final boolean is2faEnabled;
    private final int webauthnCount;
    private final List<String> oauthProviders;
    private final LocalDateTime lastLoginAt;
    private final LocalDateTime createdAt;
    private final String systemRole;
}
