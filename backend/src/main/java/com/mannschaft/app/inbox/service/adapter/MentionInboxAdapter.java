package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.service.InboxDedupeKeyResolver;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.inbox.service.InboxSourceAdapter;
import com.mannschaft.app.mention.entity.MentionEntity;
import com.mannschaft.app.mention.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F04.11 統合通知インボックス：MENTION ソースアダプタ（F04.1 mentions）。
 *
 * <p>本人宛て（{@code mentioned_user_id} 一致）のメンションを作成日時降順で取得し、統一 DTO へ正規化する
 * （読み取りのみ・書き込み越境なし＝CLAUDE.md 原則5）。title/excerpt は {@code content_snippet}、
 * occurredAt は {@code created_at}、priority は一律 {@link InboxPriority#HIGH}（本人宛て直接言及・01 §3.2）。
 * actionUrl は {@code target_type + target_id} から {@code MentionService} と同じ規則で導出する。
 * 設計書: 03_business_logic.md §2・04_security_operations.md §1.2。</p>
 */
@Component
@RequiredArgsConstructor
public class MentionInboxAdapter implements InboxSourceAdapter {

    private final MentionRepository mentionRepository;
    private final InboxPriorityNormalizer priorityNormalizer;
    private final InboxDedupeKeyResolver dedupeKeyResolver;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.MENTION;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        // Phase3 ③：境界付きウィンドウ＝新着順の上位 window 件のみ（無制限 fetch を根絶）。
        // MENTION の priority は一律 HIGH のため、自ソース内の順序は新着順＝集約側の全順序と整合する
        // （同 priority 内では occurredAt → タイブレークで決定的）。
        return mentionRepository
                .findByMentionedUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, window)).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public boolean isVisibleTo(Long userId, Long sourceId) {
        return mentionRepository.findById(sourceId)
                .filter(m -> userId.equals(m.getMentionedUserId()))
                .isPresent();
    }

    private InboxItemDto toDto(MentionEntity m) {
        InboxPriority priority = priorityNormalizer.normalize(InboxSourceType.MENTION, null);

        InboxState sourceState = Boolean.TRUE.equals(m.getIsRead()) ? InboxState.READ : InboxState.UNREAD;

        // 名寄せ（Phase 3 ①）：メンションの終端 targetType + targetId を正規化。
        // TIMELINE_COMMENT 等の ReferenceType 未マッピング語は正規化不能＝自分自身キーで畳まない。
        String selfKey = InboxSourceType.MENTION.name() + ":" + m.getId();
        String canonicalRef = dedupeKeyResolver.canonicalRefOrSelf(
                m.getTargetType(), m.getTargetId(), selfKey);

        return new InboxItemDto(
                selfKey,
                InboxSourceType.MENTION,
                m.getId(),
                m.getContentSnippet(),
                m.getContentSnippet(),
                priority,
                null,
                resolveUrl(m.getTargetType(), m.getTargetId()),
                m.getCreatedAt(),
                sourceState,
                null,
                List.of(),
                canonicalRef,
                1,
                List.of(new InboxItemRef(InboxSourceType.MENTION, m.getId())));
    }

    /**
     * {@code target_type + target_id} から遷移先 URL を導出する（{@code MentionService.resolveUrl} と同一規則）。
     */
    private String resolveUrl(String targetType, Long targetId) {
        if (targetType == null) {
            return "/";
        }
        return switch (targetType) {
            case "TIMELINE_POST" -> "/timeline/" + targetId;
            case "CHAT_MESSAGE" -> "/chat?message=" + targetId;
            case "TIMELINE_COMMENT" -> "/timeline/" + targetId;
            case "BULLETIN_THREAD" -> "/bulletin/" + targetId;
            default -> "/";
        };
    }
}
