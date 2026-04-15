package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.DmReceiveFrom;
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
    /** ISO 3166-1 alpha-2 国コード。null の場合は更新しない。 */
    private final String countryCode;
    private final String timezone;
    private final Boolean isSearchable;
    private final String avatarUrl;
    private final String phoneNumber;
    private final String postalCode;
    /** DM受信制限設定。null の場合は更新しない。 */
    private final DmReceiveFrom dmReceiveFrom;
}
