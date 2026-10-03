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
 *   <li>所有者一致検証: {@link InboxAccessGuard#requireOwnedLabel} に一元化（{@code findByIdAndUserId} で
 *       id と所有者を同時に条件化）。不一致/不存在/論理削除済みは一律 {@code INBOX_LABEL_NOT_FOUND}
 *       （存在秘匿・IDOR 対策）</li>
 *   <li>上限: 1 ユーザー 20 ラベル / 1 通知 10 ラベル</li>
 *   <li>同名重複: 現役（{@code deleted_at IS NULL}）の同名のみ禁止</li>
 *   <li>付与時は対象通知の可視性も検証（{@link InboxAccessGuard#requireVisibleSource}・
 *       他人宛て通知へのリンク作成を防止）</li>
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
    private final InboxAccessGuard inboxAccessGuard;

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

        // 対象通知が本人に可視か（他人宛て通知へのリンク作成を防止・§1.2）
        inboxAccessGuard.requireVisibleSource(userId, sourceType, sourceId);

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

    /**
     * 自動ラベリング提案の 1 タップ付与（案C・冪等・find-or-create）。
     *
     * <p>処理（設計書 02_api_design.md §3.5a / 03_business_logic.md §10）:</p>
     * <ol>
     *   <li>対象通知（{@code sourceType}/{@code sourceId}）が本人に可視であることを検証する
     *       （{@link InboxAccessGuard#requireVisibleSource}）。<b>この検証を find-or-create より
     *       先に行うことで、未認可の書き込み（ラベル作成）が一切発生しないことを保証する</b>
     *       （認可は副作用より前に置く）。</li>
     *   <li>同名の現役ラベルを探す（find）。無ければ {@link #createLabel} で作成する
     *       （上限 20 超は {@code INBOX_LABEL_LIMIT_EXCEEDED}・色形式不正は {@code COMMON_001}）。</li>
     *   <li>そのラベルを {@link #assignLabel} で当該通知に付与する
     *       （可視性は 1. と同一判定で {@link #assignLabel} 内でも再検証・1 通知 10 ラベル上限・
     *       <b>重複は冪等</b>に正常終了）。</li>
     * </ol>
     *
     * <p><b>冪等</b>: 同名ラベルが既にあり既に付与済みなら、作成も再付与もせず付与後の {@link LabelDto} を返す。
     * 新規エラーコードは設けず既存（LIMIT/PER_ITEM/NAME 形式系）を再利用する。</p>
     *
     * @return 付与済みラベルの {@link LabelDto}
     */
    @Transactional
    public LabelDto suggestApply(Long userId, String name, String color,
                                 InboxSourceType sourceType, Long sourceId) {
        // 0. 可視性検証を副作用（find-or-create・付与）より前に置く（他人宛て通知は INBOX_SOURCE_NOT_FOUND）
        inboxAccessGuard.requireVisibleSource(userId, sourceType, sourceId);

        String trimmedName = normalizeName(name);

        // 1. find-or-create（現役同名があれば再利用＝重複作成しない）
        LabelDto label = labelRepository.findByUserIdAndName(userId, trimmedName)
                .map(this::toDto)
                .orElseGet(() -> createLabel(userId, trimmedName, color, null));

        // 2. 付与（重複は冪等・可視性は 0. と同一判定で再検証・上限は assignLabel が検証）
        assignLabel(userId, label.id(), sourceType, sourceId);

        return label;
    }

    // ─────────────────────────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────────────────────────

    /**
     * 所有者一致検証付きのラベル取得。不一致/不存在/論理削除済みは一律 {@code INBOX_LABEL_NOT_FOUND}
     * （存在秘匿・IDOR 対策。{@code @SQLRestriction} により論理削除済みは findByIdAndUserId で除外される）。
     */
    private NotificationLabelEntity findOwnLabelOrThrow(UUID labelId, Long userId) {
        return inboxAccessGuard.requireOwnedLabel(userId, labelId);
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
