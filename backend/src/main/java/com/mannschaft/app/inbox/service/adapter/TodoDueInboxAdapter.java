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
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoAssigneeRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * F04.11 統合通知インボックス：TODO_DUE ソースアダプタ（F02.3 todos）。
 *
 * <p>本人担当・{@code status IN (OPEN, IN_PROGRESS)}・{@code due_date} が近接/超過の TODO のみを
 * 統一 DTO へ正規化する（読み取りのみ）。occurredAt は due_date 基準・既読概念なし（常に UNREAD 相当）。
 * priority は {@link InboxPriorityNormalizer#normalizeTodoDue} で due_date からユーザー TZ 暦日で導出。
 * 設計書: 03_business_logic.md §2。</p>
 */
@Component
@RequiredArgsConstructor
public class TodoDueInboxAdapter implements InboxSourceAdapter {

    /** 「近接」とみなす最大日数（これより先の期限はインボックス対象外）。設計書 §3 の「3 日内」を上限に余裕を持たせる。 */
    private static final long NEAR_DAYS = 3;

    private final TodoRepository todoRepository;
    private final TodoAssigneeRepository todoAssigneeRepository;
    private final InboxPriorityNormalizer priorityNormalizer;

    @Override
    public InboxSourceType sourceType() {
        return InboxSourceType.TODO_DUE;
    }

    @Override
    public List<InboxItemDto> fetch(Long userId, int window) {
        if (window <= 0) {
            return List.of();
        }
        NormalizationContext ctx = currentContext();
        LocalDate today = ctx.now().atZone(ctx.zoneId()).toLocalDate();
        // 近接上限日（ユーザー TZ 暦日）。due_date <= cutoff が「期限切れ ∨ NEAR_DAYS 以内」と等価。
        LocalDate cutoff = today.plusDays(NEAR_DAYS);

        // Phase3 ③：DB 側で「未完了 ∧ 近接/超過」に絞ってから window 件まで取得する（無制限 fetch を根絶）。
        // due_date 昇順（期限切れ→当日→近接）＝集約側 priority 降順（URGENT→HIGH→NORMAL）と整合し、
        // 上位 window 件にこのソースのグローバル上位候補が漏れなく含まれる。
        return todoRepository.findMyDueTodos(userId, cutoff, PageRequest.of(0, window)).stream()
                .map(t -> toDto(t, ctx))
                .toList();
    }

    @Override
    public boolean isVisibleTo(Long userId, Long sourceId) {
        if (!todoAssigneeRepository.existsByTodoIdAndUserId(sourceId, userId)) {
            return false;
        }
        return todoRepository.findByIdAndDeletedAtIsNull(sourceId)
                .filter(this::isActive)
                .filter(t -> t.getDueDate() != null)
                .isPresent();
    }

    private boolean isActive(TodoEntity t) {
        return t.getStatus() == TodoStatus.OPEN || t.getStatus() == TodoStatus.IN_PROGRESS;
    }

    private InboxItemDto toDto(TodoEntity t, NormalizationContext ctx) {
        // due_date（+ due_time があれば）を occurredAt の LocalDateTime に組み立てる。
        LocalDateTime occurredAt = t.getDueDate().atTime(
                t.getDueTime() != null ? t.getDueTime() : LocalTime.MIDNIGHT);

        InboxPriority priority = priorityNormalizer.normalizeTodoDue(occurredAt, ctx);

        InboxItemDto.ScopeDto scope = new InboxItemDto.ScopeDto(
                t.getScopeType() != null ? t.getScopeType().name() : null,
                t.getScopeId(),
                null);

        // 名寄せ（Phase 3 ①）：TODO は固有実体（畳む相手がいない）＝常に自分自身キー。
        String selfKey = InboxSourceType.TODO_DUE.name() + ":" + t.getId();

        return new InboxItemDto(
                selfKey,
                InboxSourceType.TODO_DUE,
                t.getId(),
                t.getTitle(),
                t.getDescription(),
                priority,
                scope,
                "/todos/" + t.getId(),
                occurredAt,
                InboxState.UNREAD,
                null,
                List.of(),
                selfKey,
                1,
                List.of(new InboxItemRef(InboxSourceType.TODO_DUE, t.getId())));
    }

    /** 現在のユーザー TZ で正規化コンテキストを構築する（未セット時は UTC）。 */
    private NormalizationContext currentContext() {
        ZoneId zone = TimezoneContextHolder.get();
        return new NormalizationContext(LocalDateTime.now(), zone);
    }
}
