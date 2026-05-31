package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：ラベルサービス（CRUD・付与/解除・上限検証）。
 *
 * <p>手本: {@code ActionMemoTagService}。{@code @Transactional} は inbox ドメイン内に閉じる（原則5）。
 * 設計書: 02_api_design.md §3.4 / 03_business_logic.md §1。</p>
 *
 * <p><b>骨組み（一陣）</b>: MVP では未使用。ラベル機能本体は Phase 2 で実装する。
 * 現段階ではコンパイルが通る空骨格。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxLabelService {

    private final NotificationLabelRepository labelRepository;
    private final InboxLabelLinkRepository labelLinkRepository;

    /**
     * ユーザーの現役ラベル一覧を表示順で取得する。
     */
    @Transactional(readOnly = true)
    public List<LabelDto> getLabels(Long userId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * ラベルを作成する（上限 20・同名重複検証）。
     */
    @Transactional
    public LabelDto createLabel(Long userId, String name, String color, String icon) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * ラベルを更新する（名前/色/アイコン/順序）。
     */
    @Transactional
    public LabelDto updateLabel(Long userId, UUID labelId, String name, String color, String icon, Integer sortOrder) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * ラベルを論理削除する。
     */
    @Transactional
    public void deleteLabel(Long userId, UUID labelId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * ラベルを通知へ付与する（重複は冪等・1 通知 10 ラベル上限）。
     */
    @Transactional
    public void assignLabel(Long userId, UUID labelId, InboxSourceType sourceType, Long sourceId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * ラベル付与を解除する。
     */
    @Transactional
    public void unassignLabel(Long userId, UUID labelId, InboxSourceType sourceType, Long sourceId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
