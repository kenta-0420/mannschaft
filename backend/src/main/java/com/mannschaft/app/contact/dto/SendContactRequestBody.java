package com.mannschaft.app.contact.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 連絡先申請送信リクエスト。
 */
@Getter
@NoArgsConstructor
public class SendContactRequestBody {

    @NotNull
    private Long targetUserId;

    @Size(max = 200)
    private String message;

    private String sourceType;
}
