package com.mannschaft.app.committee.dto;

import com.mannschaft.app.committee.entity.CommitteeRole;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 委員会メンバーロール変更リクエスト DTO。
 */
@Getter
@NoArgsConstructor
public class UpdateMemberRoleRequest {

    /** 変更後のロール */
    @NotNull
    private CommitteeRole role;
}
