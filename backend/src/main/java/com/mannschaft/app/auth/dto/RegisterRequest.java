package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * ユーザー新規登録リクエスト。
 */
@Getter
public class RegisterRequest {

    @NotBlank
    @Email
    private final String email;

    @NotBlank
    @Size(min = 8)
    private final String password;

    @NotBlank
    @Size(max = 50)
    private final String lastName;

    @NotBlank
    @Size(max = 50)
    private final String firstName;

    @NotBlank
    @Size(max = 50)
    private final String displayName;

    private final String postalCode;
    private final String locale;
    private final String timezone;

    /** ベータ招待トークン（nullable）。ベータ制限ON時に必須となる。 */
    private final String inviteToken;

    @JsonCreator
    public RegisterRequest(
            @JsonProperty("email") String email,
            @JsonProperty("password") String password,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("postalCode") String postalCode,
            @JsonProperty("locale") String locale,
            @JsonProperty("timezone") String timezone,
            @JsonProperty("inviteToken") String inviteToken) {
        this.email = email;
        this.password = password;
        this.lastName = lastName;
        this.firstName = firstName;
        this.displayName = displayName;
        this.postalCode = postalCode;
        this.locale = locale;
        this.timezone = timezone;
        this.inviteToken = inviteToken;
    }
}
