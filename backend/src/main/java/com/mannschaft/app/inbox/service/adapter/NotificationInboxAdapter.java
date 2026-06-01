package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.inbox.InboxNotificationTypes;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.service.InboxDedupeKeyResolver;
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
 * <p>{@code notifications} を本人宛て・<b>InboxPriority 相当の優先度順（URGENT→HIGH→NORMAL→LOW）→
 * 作成日時降順</b>で境界付きウィンドウ取得し、統一 DTO へ正規化する
 * （読み取りのみ・書き込み越境なし＝CLAUDE.md 原則5）。triage 状態/ラベルは集約サービスが被せる。
 * 設計書: 03_business_logic.md §2・§4.1。</p>
 *
 * <p><b>取りこぼし根治（Phase3 ③）</b>: 取得順を集約サービスのグローバル全順序（priority 第一）に
 * 一致させるため、created_at 降順のみの旧クエリではなく
 * {@code findInboxByUserIdOrderByPriorityThenCreatedAtDesc}（priority 第一順）を使う。これにより
 * 「古いが高 priority の通知」が window 外へ脱落する欠落を根絶する。</p>
 */
@Component
@RequiredArgsConstructor
public class NotificationInboxAdapter implements InboxSourceAdapter {

    private final NotificationRepository notificationRepository;
    private final InboxPriorityNormalizer priorityNormalizer;
    private final InboxDedupeKeyResolver dedupeKeyResolver;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.NOTIFICATION;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        // F04.11 Phase3 ②：スヌーズ復帰 push（INBOX_SNOOZE_REVIVAL）はインボックス受信箱に
        // 再流入させない（自己増殖の防止）。ベル/通知一覧には出るが、ここでは除外する。
        // Phase3 ③：境界付きウィンドウ＝ window 件まで（無制限 fetch を根絶）。さらに取得順を
        // priority 第一（URGENT→HIGH→NORMAL→LOW）→ created_at 降順にし、集約のグローバル全順序と
        // 一致させる。これで「古いが高 priority」の通知が window 外へ脱落する取りこぼしを根絶する。
        return notificationRepository
                .findInboxByUserIdOrderByPriorityThenCreatedAtDesc(
                        userId, InboxNotificationTypes.INBOX_SNOOZE_REVIVAL,
                        PageRequest.of(0, window))
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

        // 名寄せ（Phase 3 ①）：通知の終端 sourceType + sourceId を正規化。不能なら自分自身キーで畳まない。
        String selfKey = InboxSourceType.NOTIFICATION.name() + ":" + n.getId();
        String canonicalRef = dedupeKeyResolver.canonicalRefOrSelf(
                n.getSourceType(), n.getSourceId(), selfKey);

        return new InboxItemDto(
                selfKey,
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
                List.of(),
                canonicalRef,
                1,
                List.of(new InboxItemRef(InboxSourceType.NOTIFICATION, n.getId())));
    }
}
