package com.mannschaft.app.notification.confirmable.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 宛先の見込み件数プレビューリクエスト（軍議第8版確定稿 §3.3）。
 *
 * <p>{@code POST .../confirmable-notifications/recipient-preview}。認可は送信 API と同じ。</p>
 */
@Getter
@NoArgsConstructor
public class ConfirmableRecipientPreviewRequest {

    private List<ConfirmableTargetSpec> targets;

    private UUID recipientGroupId;
}
