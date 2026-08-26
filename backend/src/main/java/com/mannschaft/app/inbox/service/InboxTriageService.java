package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * F04.11 統合通知インボックス：triage サービス（スヌーズ/アーカイブ）。
 *
 * <p>{@code inbox_item_states} の upsert・遅延物理削除を行う（手本: {@code NotificationService.snoozeNotification} の検証）。
 * {@code @Transactional} は inbox ドメイン内に閉じる（CLAUDE.md 原則5）。設計書: 03_business_logic.md §1。</p>
 *
 * <p>IDOR 防止: 既存オーバーレイ行が無い（＝初回 triage）場合のみ、対象通知が本人に可視かを
 * {@link InboxAccessGuard#requireVisibleSource} で書き込み前検証する。可視でなければ
 * {@code INBOX_SOURCE_NOT_FOUND} を投げ、オーバーレイ行を作らない（設計書 04_security_operations.md §1.2）。
 * 既存行がある場合は過去に可視だった証左のため再検証を省く。解除系（unsnooze/unarchive）は
 * {@code (user_id, source_type, source_id)} でオーバーレイ行を引くため、常に呼び出しユーザー自身の
 * 行のみを操作する（他ユーザーの行には到達しない）。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxTriageService {

    private final InboxItemStateRepository itemStateRepository;
    private final InboxAccessGuard inboxAccessGuard;

    /** アプリ全体の JVM 既定 TZ（{@code TimeZoneConfig} で Asia/Tokyo 固定）に合わせた壁時計変換先。 */
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Tokyo");

    /**
     * 通知をスヌーズする（upsert）。過去時刻は拒否。
     *
     * <p>{@code snoozedUntil} は絶対時刻（オフセット付き）で受け取り、JST 壁時計（{@link LocalDateTime}）へ
     * 変換してから保存する。これにより、フロントが {@code .toISOString()}（UTC）で送っても比較基準である
     * {@code LocalDateTime.now()}（JST 固定 JVM）と同じ土俵に揃い、約 9 時間のずれが解消する。</p>
     *
     * @return 更新後の {@code InboxItem}（楽観更新の確定反映用）
     */
    @Transactional
    public InboxItemDto snooze(Long userId, InboxSourceType sourceType, Long sourceId, OffsetDateTime snoozedUntil) {
        if (snoozedUntil == null) {
            throw new BusinessException(InboxErrorCode.INBOX_INVALID_SNOOZE_TIME);
        }
        // 絶対時刻 → JST 壁時計に変換（保存・比較は LocalDateTime の現状ストレージに合わせる）。
        LocalDateTime snoozedUntilJst = snoozedUntil.atZoneSameInstant(APP_ZONE).toLocalDateTime();
        if (snoozedUntilJst.isBefore(LocalDateTime.now())) {
            throw new BusinessException(InboxErrorCode.INBOX_INVALID_SNOOZE_TIME);
        }
        InboxItemStateEntity row = loadOrCreate(userId, sourceType, sourceId);
        row.setSnoozedUntil(snoozedUntilJst);
        // F04.11 Phase3 ②：再スヌーズ（snoozed_until 更新）時は復帰 push 送信済みフラグを
        // NULL に戻し、新しい復帰期限到来時に再度 1 度だけ push できるようにする。
        row.setSnoozeNotifiedAt(null);
        InboxItemStateEntity saved = itemStateRepository.save(row);
        return toDto(saved);
    }

    /**
     * スヌーズを解除する。両カラムが NULL になったら行を物理削除する。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto unsnooze(Long userId, InboxSourceType sourceType, Long sourceId) {
        InboxItemStateEntity row = requireExisting(userId, sourceType, sourceId);
        row.setSnoozedUntil(null);
        return saveOrDelete(row);
    }

    /**
     * 通知をアーカイブする（保管庫へ・upsert）。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto archive(Long userId, InboxSourceType sourceType, Long sourceId) {
        InboxItemStateEntity row = loadOrCreate(userId, sourceType, sourceId);
        row.setArchivedAt(LocalDateTime.now());
        InboxItemStateEntity saved = itemStateRepository.save(row);
        return toDto(saved);
    }

    /**
     * アーカイブを解除する（受信箱へ戻す）。両カラムが NULL になったら行を物理削除する。
     *
     * @return 更新後の {@code InboxItem}
     */
    @Transactional
    public InboxItemDto unarchive(Long userId, InboxSourceType sourceType, Long sourceId) {
        InboxItemStateEntity row = requireExisting(userId, sourceType, sourceId);
        row.setArchivedAt(null);
        return saveOrDelete(row);
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────────────────────────

    /**
     * 既存オーバーレイ行を取得する。無ければ可視性検証のうえ新規行を生成する（永続化はしない）。
     */
    private InboxItemStateEntity loadOrCreate(Long userId, InboxSourceType sourceType, Long sourceId) {
        Optional<InboxItemStateEntity> existing =
                itemStateRepository.findByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // 初回 triage：本人に可視な通知かを書き込み前に検証（IDOR 防止）。
        inboxAccessGuard.requireVisibleSource(userId, sourceType, sourceId);
        InboxItemStateEntity row = new InboxItemStateEntity();
        row.setUserId(userId);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        return row;
    }

    /**
     * 既存オーバーレイ行を必須取得する（解除系で使用）。無ければ何も解除できないため
     * {@code INBOX_SOURCE_NOT_FOUND}（可視性検証も兼ねる）。
     */
    private InboxItemStateEntity requireExisting(Long userId, InboxSourceType sourceType, Long sourceId) {
        return itemStateRepository.findByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId)
                .orElseThrow(() -> new BusinessException(InboxErrorCode.INBOX_SOURCE_NOT_FOUND));
    }

    /**
     * 両カラム NULL なら物理削除、片方でも残っていれば update。
     */
    private InboxItemDto saveOrDelete(InboxItemStateEntity row) {
        if (row.getSnoozedUntil() == null && row.getArchivedAt() == null) {
            itemStateRepository.delete(row);
            return toDto(row);
        }
        InboxItemStateEntity saved = itemStateRepository.save(row);
        return toDto(saved);
    }

    /**
     * triage 結果（オーバーレイ確定状態）を軽量 DTO に詰める。タイトル等のソース情報は持たず
     * 状態反映に必要な最小フィールドのみ（楽観更新の確定反映に使用）。
     */
    private InboxItemDto toDto(InboxItemStateEntity row) {
        InboxState state;
        if (row.getArchivedAt() != null) {
            state = InboxState.ARCHIVED;
        } else if (row.getSnoozedUntil() != null && row.getSnoozedUntil().isAfter(LocalDateTime.now())) {
            state = InboxState.SNOOZED;
        } else {
            state = InboxState.UNREAD;
        }
        // triage の軽量 DTO は単一項目の状態確定反映専用。名寄せ畳み込みは集約経路でのみ行うため、
        // canonicalRef は自分自身キー・groupCount=1・groupMembers は自分 1 件とする（畳まれない）。
        String selfKey = row.getSourceType().name() + ":" + row.getSourceId();
        return new InboxItemDto(
                selfKey,
                row.getSourceType(),
                row.getSourceId(),
                null, null, null, null, null, null,
                state,
                row.getSnoozedUntil(),
                List.of(),
                selfKey,
                1,
                List.of(new InboxItemRef(row.getSourceType(), row.getSourceId())));
    }
}
