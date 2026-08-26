package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer.NormalizationContext;
import com.mannschaft.app.inbox.service.InboxSourceAdapter;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * F04.11 統合通知インボックス：CONFIRMABLE ソースアダプタ（F04.9 confirmable_notification_recipients）。
 *
 * <p>本人宛て・未確認（{@code is_confirmed = false}）・未除外（{@code excluded_at IS NULL}）の確認必須通知受信者を
 * 取得し、親 {@code confirmable_notification} から title/body/priority/deadline/actionUrl を写像して統一 DTO へ
 * 正規化する（読み取りのみ・書き込み越境なし＝CLAUDE.md 原則5）。priority は
 * {@link InboxPriorityNormalizer#normalizeConfirmable}（未確認かつ締切 24h 以内は URGENT に昇格・01 §3.2）。
 * sourceId は recipient.id（01 §3.2）、occurredAt は親 created_at。
 * 設計書: 03_business_logic.md §2/§7・04_security_operations.md §1.2。</p>
 *
 * <p><b>確認状態の正本は F04.9</b>: インボックスの archive/snooze は確認状態と独立（設計書 §7）。
 * 本アダプタはあくまで未確認の保留中通知を「仕分け対象」として読み出すだけで、確認状態は書き換えない。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableInboxAdapter implements InboxSourceAdapter {

    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final InboxPriorityNormalizer priorityNormalizer;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.CONFIRMABLE;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        NormalizationContext ctx = currentContext();
        // JOIN FETCH で親 confirmableNotification を一括取得し N+1 を防ぐ（他4ソースと同様の方式）。
        // Phase3 ③：境界付きウィンドウ＝親 created_at 降順の上位 window 件のみ（無制限 fetch を根絶）。
        // 既存の findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull は保留中一覧 API 等で引き続き使用。
        return recipientRepository
                .findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(
                        userId, PageRequest.of(0, window)).stream()
                .map(r -> toDto(r, ctx))
                .toList();
    }

    @Override
    public boolean isVisibleTo(Long userId, Long sourceId) {
        return recipientRepository.findById(sourceId)
                .filter(r -> r.getExcludedAt() == null)
                .filter(r -> r.getUser() != null && userId.equals(r.getUser().getId()))
                .isPresent();
    }

    private InboxItemDto toDto(ConfirmableNotificationRecipientEntity r, NormalizationContext ctx) {
        ConfirmableNotificationEntity parent = r.getConfirmableNotification();

        InboxPriority priority = priorityNormalizer.normalizeConfirmable(
                parentRawPriority(parent.getPriority()),
                parent.getDeadlineAt(),
                Boolean.TRUE.equals(r.getIsConfirmed()),
                ctx);

        String actionUrl = parent.getActionUrl() != null
                ? parent.getActionUrl()
                : "/confirmations/" + parent.getId();

        InboxItemDto.ScopeDto scope = new InboxItemDto.ScopeDto(
                parent.getScopeType() != null ? parent.getScopeType().name() : null,
                parent.getScopeId(),
                null);

        // 名寄せ（Phase 3 ①）：確認必須通知は固有実体（畳む相手がいない）＝常に自分自身キー。
        String selfKey = InboxSourceType.CONFIRMABLE.name() + ":" + r.getId();

        // 未確認の保留中通知のみ取得するため、ソース状態は UNREAD（確認＝READ 相当は対象外）。
        return new InboxItemDto(
                selfKey,
                InboxSourceType.CONFIRMABLE,
                r.getId(),
                parent.getTitle(),
                parent.getBody(),
                priority,
                scope,
                actionUrl,
                parent.getCreatedAt(),
                InboxState.UNREAD,
                null,
                List.of(),
                selfKey,
                1,
                List.of(new InboxItemRef(InboxSourceType.CONFIRMABLE, r.getId())));
    }

    /** 親 priority enum を normalizer が解する文字列（NORMAL/HIGH/URGENT）へ変換する。 */
    private String parentRawPriority(ConfirmableNotificationPriority priority) {
        return priority != null ? priority.name() : null;
    }

    /** 現在のユーザー TZ で正規化コンテキストを構築する（未セット時は UTC）。 */
    private NormalizationContext currentContext() {
        ZoneId zone = TimezoneContextHolder.get();
        return new NormalizationContext(LocalDateTime.now(), zone);
    }
}
