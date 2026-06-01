package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.error.InboxErrorCode;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F04.11 統合通知インボックス：ラベルサービス（CRUD・付与/解除・上限検証）。
 *
 * <p>手本: {@code ActionMemoTagService}・{@code FavoriteService}（上限 20・IDOR）。
 * {@code @Transactional} は inbox ドメイン内に閉じる（CLAUDE.md 原則5）。
 * 設計書: 02_api_design.md §3.4 / 04_security_operations.md §1・§2。</p>
 *
 * <ul>
 *   <li>所有者一致検証: ラベルは {@code findByIdAndUserId}（{@code @SQLRestriction} で論理削除済みも除外）。
 *       不一致/不存在/論理削除済みは一律 {@code INBOX_LABEL_NOT_FOUND}（存在秘匿・IDOR 対策）</li>
 *   <li>上限: 1 ユーザー 20 ラベル / 1 通知 10 ラベル</li>
 *   <li>同名重複: 現役（{@code deleted_at IS NULL}）の同名のみ禁止</li>
 *   <li>付与時は対象通知の可視性も検証（{@link InboxItemVisibilityChecker}・他人通知へのリンク作成を防止）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InboxLabelService {

    /** 設計書 01_data_model.md §2.2: 1 ユーザーあたりのラベル上限 */
    private static final int LABEL_LIMIT_PER_USER = 20;

    /** 設計書 02_api_design.md §3.4: 1 通知あたりのラベル上限 */
    private static final int LABEL_LIMIT_PER_ITEM = 10;

    /** 表示色は #RRGGBB 形式のみ許可（04_security_operations.md §2） */
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** アイコンは PrimeIcons の pi- プレフィックスのみ許可（04_security_operations.md §2） */
    private static final String ICON_PREFIX = "pi-";

    private final NotificationLabelRepository labelRepository;
    private final InboxLabelLinkRepository labelLinkRepository;
    private final InboxItemVisibilityChecker visibilityChecker;

    /**
     * ユーザーの現役ラベル一覧を表示順で取得する。
     */
    public List<LabelDto> getLabels(Long userId) {
        return labelRepository.findByUserIdOrderBySortOrderAsc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * ラベルを作成する（上限 20・同名重複・色/アイコン形式検証）。
     */
    @Transactional
    public LabelDto createLabel(Long userId, String name, String color, String icon) {
        String trimmedName = normalizeName(name);
        validateColor(color);
        validateIcon(icon);

        // 上限 20（@SQLRestriction で論理削除済みは除外されるため countByUserId でよい）
        if (labelRepository.countByUserId(userId) >= LABEL_LIMIT_PER_USER) {
            log.warn("inbox_label_limit_exceeded: userId={}", userId);
            throw new BusinessException(InboxErrorCode.INBOX_LABEL_LIMIT_EXCEEDED);
        }
        // 現役同名重複禁止
        if (labelRepository.existsByUserIdAndName(userId, trimmedName)) {
            throw new BusinessException(InboxErrorCode.INBOX_LABEL_NAME_DUPLICATE);
        }

        NotificationLabelEntity entity = new NotificationLabelEntity();
        entity.setUserId(userId);
        entity.setName(trimmedName);
        entity.setColor(color);
        entity.setIcon(icon);
        entity.setSortOrder(0);

        NotificationLabelEntity saved = labelRepository.save(entity);
        log.info("inbox_label_created: labelId={} userId={}", saved.getId(), userId);
        return toDto(saved);
    }

    /**
     * ラベルを更新する（名前/色/アイコン/順序）。所有者一致検証・改名時の重複検証付き。
     */
    @Transactional
    public LabelDto updateLabel(Long userId, UUID labelId, String name, String color, String icon, Integer sortOrder) {
        NotificationLabelEntity label = findOwnLabelOrThrow(labelId, userId);

        if (name != null && !name.isBlank()) {
            String trimmedName = normalizeName(name);
            // 名前を実際に変更する場合のみ現役同名重複を検証する（同名据え置きは許容）
            if (!trimmedName.equals(label.getName())
                    && labelRepository.existsByUserIdAndName(userId, trimmedName)) {
                throw new BusinessException(InboxErrorCode.INBOX_LABEL_NAME_DUPLICATE);
            }
            label.setName(trimmedName);
        }
        if (color != null) {
            validateColor(color);
            label.setColor(color);
        }
        if (icon != null) {
            validateIcon(icon);
            label.setIcon(icon);
        }
        if (sortOrder != null) {
            label.setSortOrder(sortOrder);
        }

        NotificationLabelEntity saved = labelRepository.save(label);
        log.info("inbox_label_updated: labelId={} userId={}", saved.getId(), userId);
        return toDto(saved);
    }

    /**
     * ラベルを論理削除する。中間テーブル（inbox_label_links）は残す
     * （一覧時に現役ラベルのみ join され、孤児リンクは脱落する＝設計書 §2.3）。
     */
    @Transactional
    public void deleteLabel(Long userId, UUID labelId) {
        NotificationLabelEntity label = findOwnLabelOrThrow(labelId, userId);
        label.softDelete();
        labelRepository.save(label);
        log.info("inbox_label_deleted: labelId={} userId={}", labelId, userId);
    }

    /**
     * ラベルを通知へ付与する（重複は冪等・1 通知 10 ラベル上限・対象通知の可視性検証）。
     */
    @Transactional
    public void assignLabel(Long userId, UUID labelId, InboxSourceType sourceType, Long sourceId) {
        // ラベル所有検証（不存在/他人/論理削除済みは一律 404）
        findOwnLabelOrThrow(labelId, userId);

        // 対象通知が本人に可視か（他人通知へのリンク作成によるテーブル肥大化攻撃を防止・§1.2）
        if (!visibilityChecker.isVisibleTo(userId, sourceType, sourceId)) {
            throw new BusinessException(InboxErrorCode.INBOX_SOURCE_NOT_FOUND);
        }

        // 冪等: 既に同じ付与があれば何もしない
        if (labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(labelId, sourceType, sourceId)) {
            return;
        }

        // 1 通知 10 ラベル上限
        if (labelLinkRepository.countByUserIdAndSourceTypeAndSourceId(userId, sourceType, sourceId)
                >= LABEL_LIMIT_PER_ITEM) {
            throw new BusinessException(InboxErrorCode.INBOX_LABEL_PER_ITEM_EXCEEDED);
        }

        InboxLabelLinkEntity link = new InboxLabelLinkEntity();
        link.setLabelId(labelId);
        link.setUserId(userId);
        link.setSourceType(sourceType);
        link.setSourceId(sourceId);
        labelLinkRepository.save(link);
        log.info("inbox_label_assigned: labelId={} userId={} source={}:{}", labelId, userId, sourceType, sourceId);
    }

    /**
     * ラベル付与を解除する。リンクが無ければ冪等に無視する。
     * 所有は label 側（{@code label_id} がそのユーザーのラベルか）で担保され、
     * リンクは {@code user_id} を冗長保持しないキーでも安全に絞れるが、念のため所有も検証する。
     */
    @Transactional
    public void unassignLabel(Long userId, UUID labelId, InboxSourceType sourceType, Long sourceId) {
        // ラベル所有検証（他人のラベル ID を指定した解除を防ぐ・存在秘匿）
        findOwnLabelOrThrow(labelId, userId);

        Optional<InboxLabelLinkEntity> link =
                labelLinkRepository.findByLabelIdAndSourceTypeAndSourceId(labelId, sourceType, sourceId);
        link.ifPresent(l -> {
            labelLinkRepository.delete(l);
            log.info("inbox_label_unassigned: labelId={} userId={} source={}:{}",
                    labelId, userId, sourceType, sourceId);
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────────────────────────

    /**
     * 所有者一致検証付きのラベル取得。不一致/不存在/論理削除済みは一律 {@code INBOX_LABEL_NOT_FOUND}
     * （存在秘匿・IDOR 対策。{@code @SQLRestriction} により論理削除済みは findByIdAndUserId で除外される）。
     */
    private NotificationLabelEntity findOwnLabelOrThrow(UUID labelId, Long userId) {
        return labelRepository.findByIdAndUserId(labelId, userId)
                .orElseThrow(() -> new BusinessException(InboxErrorCode.INBOX_LABEL_NOT_FOUND));
    }

    private String normalizeName(String name) {
        return name == null ? null : name.trim();
    }

    private void validateColor(String color) {
        if (color != null && !COLOR_PATTERN.matcher(color).matches()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    private void validateIcon(String icon) {
        if (icon != null && !icon.startsWith(ICON_PREFIX)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    private LabelDto toDto(NotificationLabelEntity e) {
        return new LabelDto(e.getId(), e.getName(), e.getColor(), e.getIcon(), e.getSortOrder());
    }
}
