package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プロフィール更新リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class UpdateProfileRequest {

    private final String lastName;
    private final String firstName;
    private final String lastNameKana;
    private final String firstNameKana;
    private final String displayName;
    private final String nickname2;
    private final String locale;
    private final String timezone;
    private final Boolean isSearchable;
    private final String avatarUrl;
    private final String phoneNumber;
    private final String postalCode;
}
