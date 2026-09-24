package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 宛先の見込み件数プレビューリクエスト（軍議第8版確定稿 §3.3）。
 *
 * <p>{@code POST .../confirmable-notifications/recipient-preview}。認可は送信 API と同じ。
 * 全引数コンストラクタは、送信 API（{@code ConfirmableNotificationService#sendAsync}）が
 * 解決済みの宛先から本リクエストを内部的に組み立てるために使う（CMP-260920-1040）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmableRecipientPreviewRequest {

    private List<ConfirmableTargetSpec> targets;

    private UUID recipientGroupId;
}
