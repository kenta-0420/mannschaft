package com.mannschaft.app.contact.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 申請事前拒否追加リクエスト。
 */
@Getter
@NoArgsConstructor
public class AddContactRequestBlockBody {

    @NotNull
    private Long targetUserId;
}
