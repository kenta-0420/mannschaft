package com.mannschaft.app.notification.confirmable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループ作成・更新リクエスト（軍議第8版確定稿 §3.1・AC-31）。
 */
@Getter
@NoArgsConstructor
public class ConfirmableRecipientGroupCreateRequest {

    @NotBlank
    private String name;

    @NotEmpty
    private List<ConfirmableTargetSpec> targets;
}
