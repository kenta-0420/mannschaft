package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 封緘解除申請リクエスト DTO（F09.15 S2-C）。
 *
 * <p>申請者が解除したい事前登録 ID と解除理由を指定する。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnsealRequestCreateRequest {

    /** 対象事前登録 ID（UUIDv7）。 */
    @NotNull(message = "事前登録IDは必須です")
    @JsonAlias("pre_registration_id")
    private UUID preRegistrationId;

    /** 解除理由（必須・1〜500文字）。 */
    @NotBlank(message = "解除理由は必須です")
    @Size(max = 500, message = "解除理由は500文字以内です")
    @JsonAlias("reason")
    private String reason;
}
