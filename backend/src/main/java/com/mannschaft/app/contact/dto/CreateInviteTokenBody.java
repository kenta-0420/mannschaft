package com.mannschaft.app.contact.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 招待トークン発行リクエスト。
 */
@Getter
@NoArgsConstructor
public class CreateInviteTokenBody {

    @Size(max = 50)
    private String label;

    /** 利用回数上限。null=無制限 */
    private Integer maxUses;

    /**
     * 有効期限: 1d, 7d, 30d, unlimited。
     * デフォルト: 7d
     */
    @Pattern(regexp = "^(1d|7d|30d|unlimited)$",
             message = "expiresIn は 1d / 7d / 30d / unlimited のいずれかです")
    private String expiresIn;
}
