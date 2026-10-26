package com.mannschaft.app.succession.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 封緘解除承認リクエスト DTO（F09.15 S2-C）。
 *
 * <p>一次承認・二次承認共通。コメントは任意。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnsealApprovalRequest {

    /** 承認コメント（任意・500文字以内）。 */
    @Size(max = 500, message = "コメントは500文字以内です")
    private String comment;
}
