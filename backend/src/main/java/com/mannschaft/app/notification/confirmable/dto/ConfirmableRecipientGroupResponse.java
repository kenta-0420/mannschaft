package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループレスポンス（軍議第8版確定稿 §3.1）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmableRecipientGroupResponse {

    private UUID id;

    private String name;

    private List<ConfirmableTargetSpec> targets;

    private LocalDateTime createdAt;
}
