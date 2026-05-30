package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F04.11 統合通知インボックス：triage サービス（スヌーズ/アーカイブ）。
 *
 * <p>{@code inbox_item_states} の upsert・遅延削除を行う（手本: {@code NotificationService.snoozeNotification} の検証）。
 * {@code @Transactional} は inbox ドメイン内に閉じる（CLAUDE.md 原則5）。設計書: 03_business_logic.md §1。</p>
 *
 * <p><b>骨組み（一陣）</b>: ロジック本体は三陣で実装する。現段階ではコンパイルが通る空骨格。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxTriageService {

    private final InboxItemStateRepository itemStateRepository;

    /**
     * 通知をスヌーズする（upsert）。過去時刻は拒否。
     *
     * @return 更新後の {@code InboxItem}（楽観更新の確定反映用）
     */
    @Transactional
    public InboxItemDto snooze(Long userId, InboxSourceType sourceType, Long sourceId, LocalDateTime snoozedUntil) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * スヌーズを解除する。両カラムが NULL になったら行を物理削除する。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto unsnooze(Long userId, InboxSourceType sourceType, Long sourceId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * 通知をアーカイブする（保管庫へ・upsert）。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto archive(Long userId, InboxSourceType sourceType, Long sourceId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * アーカイブを解除する（受信箱へ戻す）。両カラムが NULL になったら行を物理削除する。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto unarchive(Long userId, InboxSourceType sourceType, Long sourceId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
