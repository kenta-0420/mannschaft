package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.service.InboxSourceAdapter;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F04.11 統合通知インボックス：NOTIFICATION ソースアダプタ（F04.3 notifications）。
 *
 * <p>{@code notifications} を本人宛て・作成日時降順でハードリミット取得し、統一 DTO へ正規化する
 * （読み取りのみ・書き込み越境なし＝CLAUDE.md 原則5）。triage 状態/ラベルは集約サービスが被せる。
 * 設計書: 03_business_logic.md §2。</p>
 */
@Component
@RequiredArgsConstructor
public class NotificationInboxAdapter implements InboxSourceAdapter {

    /** ソース毎ハードリミット（深いページ網羅は非保証＝「直近の仕分け場」割り切り。設計書 §5）。 */
    private static final int HARD_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final InboxPriorityNormalizer priorityNormalizer;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.NOTIFICATION;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId) {
        return notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, HARD_LIMIT))
                .getContent()
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public boolean isVisibleTo(Long userId, Long sourceId) {
        return notificationRepository.findByIdAndUserId(sourceId, userId).isPresent();
    }

    private InboxItemDto toDto(NotificationEntity n) {
        InboxPriority priority = priorityNormalizer.normalize(
                InboxSourceType.NOTIFICATION,
                n.getPriority() != null ? n.getPriority().name() : null);

        InboxState sourceState = n.isAlreadyRead() ? InboxState.READ : InboxState.UNREAD;

        InboxItemDto.ScopeDto scope = new InboxItemDto.ScopeDto(
                n.getScopeType() != null ? n.getScopeType().name() : null,
                n.getScopeId(),
                null);

        return new InboxItemDto(
                InboxSourceType.NOTIFICATION.name() + ":" + n.getId(),
                InboxSourceType.NOTIFICATION,
                n.getId(),
                n.getTitle(),
                n.getBody(),
                priority,
                scope,
                n.getActionUrl(),
                n.getCreatedAt(),
                sourceState,
                null,
                List.of());
    }
}
