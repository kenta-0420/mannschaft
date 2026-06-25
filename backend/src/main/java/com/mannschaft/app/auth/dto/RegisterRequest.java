package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
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

    /**
     * 表示名（displayName）。
     *
     * <p>UserEntity.displayName は {@code @Column(nullable = false)} のため必須。
     * これを欠落・空文字で受け取ると DB の NOT NULL 制約違反で 500（COMMON_999）になるため、
     * DTO 層で {@code @NotBlank} を課して 400（COMMON_001）として弾く。
     * フロントエンド（register.vue）でも displayName は必須入力。</p>
     */
    @NotBlank
    @Size(max = 50)
    private final String nickname;

    private final String postalCode;
    private final String locale;
    private final String timezone;

    /** ベータ招待トークン（nullable）。ベータ制限ON時に必須となる。 */
    private final String inviteToken;

    /** 生年月日（YYYY-MM-DD形式）。F01.9 年齢確認のため必須。 */
    private final String birthDate;

    /**
     * プライバシーポリシーへの同意フラグ（F_privacy_policy）。
     *
     * <p>登録時に {@code true} でなければならない。{@code false} の場合は
     * {@code @AssertTrue} によって 400（AUTH_PP_001）として弾かれる。</p>
     */
    @AssertTrue(message = "AUTH_PP_001")
    private final boolean privacyPolicyAccepted;

    /**
     * 同意したプライバシーポリシーのバージョン（F_privacy_policy）。
     *
     * <p>空文字・null の場合は 400（AUTH_PP_002）として弾かれる。</p>
     */
    @NotBlank(message = "AUTH_PP_002")
    @Size(max = 20, message = "AUTH_PP_003")
    private final String privacyPolicyVersion;

    @JsonCreator
    public RegisterRequest(
            @JsonProperty("email") String email,
            @JsonProperty("password") String password,
            @JsonProperty("lastName") String lastName,
            @JsonProperty("firstName") String firstName,
            @JsonProperty("nickname") String nickname,
            @JsonProperty("postalCode") String postalCode,
            @JsonProperty("locale") String locale,
            @JsonProperty("timezone") String timezone,
            @JsonProperty("inviteToken") String inviteToken,
            @JsonProperty("birth_date") String birthDate,
            @JsonProperty("privacyPolicyAccepted") boolean privacyPolicyAccepted,
            @JsonProperty("privacyPolicyVersion") String privacyPolicyVersion) {
        this.email = email;
        this.password = password;
        this.lastName = lastName;
        this.firstName = firstName;
        this.nickname = nickname;
        this.postalCode = postalCode;
        this.locale = locale;
        this.timezone = timezone;
        this.inviteToken = inviteToken;
        this.birthDate = birthDate;
        this.privacyPolicyAccepted = privacyPolicyAccepted;
        this.privacyPolicyVersion = privacyPolicyVersion;
    }
}
