package com.mannschaft.app.reflection.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@code ReferenceType.REFLECTION_ENTRY} の可視性判定 Resolver（F06.5・§6.1）。
 *
 * <p>reflection_entries は UUIDv7 主キーのため <b>UUID 経路</b>（{@link #canViewUuid} /
 * {@link #filterAccessibleUuid}）を実装し、Long 経路はデフォルトのまま fail-closed とする
 * （{@code MatchVisibilityResolver} 手本・{@code idKind()==UUID_V7}）。</p>
 *
 * <p><b>可視性ルール（MVP）</b>: theme/entry の visibility は PRIVATE 固定ゆえ「閲覧者＝所有者本人
 * （{@code entry.userId == viewerUserId}）」判定で十分。FAMILY_SHARED（保護者の学習確認）は別軍議
 * （§9.1）で追加する。マスク（§3）は本 Resolver と直交し、本文は Mapper 側でソースから null になる。</p>
 *
 * <p><b>fail-closed</b>: viewer 不明・entry 欠落・論理削除（{@code @SQLRestriction} で不在）は deny。</p>
 *
 * <p>設計: docs/features/F06.5_reflection_active_recall.md §6.1</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionEntryVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    private final ReflectionEntryRepository reflectionEntryRepository;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.REFLECTION_ENTRY;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        log.warn("REFLECTION_ENTRY resolver called via Long path (should be UUID): contentId={}", contentId);
        return false;
    }

    @Override
    public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        return Collections.emptySet();
    }

    // ─── UUID 経路（本流） ───────────────────────────────────────

    @Override
    public boolean canViewUuid(UUID contentId, Long viewerUserId) {
        if (contentId == null || viewerUserId == null) {
            return false;
        }
        // @SQLRestriction により論理削除済みは取得されない（不在＝deny）。
        Optional<ReflectionEntryEntity> opt = reflectionEntryRepository.findById(contentId);
        return opt.isPresent() && isOwner(opt.get(), viewerUserId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        // 1 SQL で一括取得し、メモリ上で所有判定（N+1 回避）。
        List<ReflectionEntryEntity> rows = reflectionEntryRepository.findAllById(contentIds);
        Set<UUID> accessible = new HashSet<>();
        for (ReflectionEntryEntity entry : rows) {
            if (entry == null || entry.getId() == null) {
                continue;
            }
            if (isOwner(entry, viewerUserId)) {
                accessible.add(entry.getId());
            }
        }
        return accessible;
    }

    /**
     * 閲覧者が当該エントリの所有者本人か判定する（MVP は PRIVATE 固定ゆえ本人のみ可視）。
     */
    private boolean isOwner(ReflectionEntryEntity entry, Long viewerUserId) {
        return entry.getUserId() != null && entry.getUserId().equals(viewerUserId);
    }
}
