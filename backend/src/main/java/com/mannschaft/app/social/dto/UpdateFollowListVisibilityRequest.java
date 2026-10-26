package com.mannschaft.app.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * フォロー一覧公開設定更新リクエストDTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateFollowListVisibilityRequest {

    /** 公開設定（"PUBLIC" / "FRIENDS_ONLY" / "PRIVATE"） */
    @NotBlank
    @Pattern(regexp = "PUBLIC|FRIENDS_ONLY|PRIVATE", message = "visibility は PUBLIC / FRIENDS_ONLY / PRIVATE のいずれかを指定してください")
    private String visibility;
}
