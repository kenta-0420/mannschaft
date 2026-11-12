package com.mannschaft.app.chat.service;

import com.mannschaft.app.role.dto.InviteCardData;
import com.mannschaft.app.role.service.MembershipInviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 部屋一覧プレビューの招待カード文言を確定する（CMP-261105-1507）。
 *
 * <p>最新メッセージの編集・削除で、一覧プレビューが招待カードに差し戻ることがある。招待カードの本文は
 * 内部マーカー {@code [招待カード]} なので、投稿時と同じ「{招待先名} への招待を送りました」
 * （F04.12 §D-14 の保存契約）に作り直す。招待先名は role ドメインの参照を要するため、chat のトランザクション
 * （D-3T）の中では引かず、{@link ChatMessageFacade} から、トランザクションの外で呼ぶ。</p>
 *
 * <p>本クラスに {@code @Transactional} を付けてはならない。招待先を解決できない場合は、
 * トランザクション内で載せた暫定文言 {@code 招待を送りました} がそのまま残る。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatInviteCardPreviewRefresher {

    private final ChatMessageService messageService;
    private final MembershipInviteService membershipInviteService;

    /**
     * チャンネルの最新メッセージが招待カードなら、招待先名入りのプレビューに確定する。
     */
    public void refresh(Long channelId) {
        messageService.findLatestInviteCardPreviewTarget(channelId).ifPresent(target -> {
            InviteCardData data = membershipInviteService.resolveInviteCardData(target.tokenId(), target.senderId());
            if (data == null || data.scopeName() == null) {
                return;
            }
            messageService.applyInviteCardPreview(
                    channelId, target.messageId(), data.scopeName() + " への招待を送りました");
        });
    }
}
