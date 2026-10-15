package com.mannschaft.app.contact.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @ハンドル更新リクエスト。
 */
@Getter
@NoArgsConstructor
public class UpdateHandleRequest {

    @Size(min = 3, max = 30)
    @Pattern(regexp = "^[a-z0-9_-]{3,30}$", message = "ハンドルは小文字英数字・アンダースコア・ハイフンで3〜30文字にしてください")
    private String contactHandle;
}
