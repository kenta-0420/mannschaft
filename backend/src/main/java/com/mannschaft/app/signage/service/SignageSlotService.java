package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.SignageSlotType;
import com.mannschaft.app.signage.entity.SignageSlotEntity;
import com.mannschaft.app.signage.repository.SignageSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * デジタルサイネージ スロット管理サービス。
 * スロットの追加・一覧・更新・削除・並び替えを担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageSlotService {

    private final SignageSlotRepository slotRepository;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * スロット追加リクエスト DTO。
     */
    public record AddSignageSlotRequest(
            SignageSlotType slotType,
            /** コンテンツソースID（blog_posts.id / schedules.id 等） */
            String contentSourceId,
            /** スロットの表示秒数 */
            Integer durationSeconds,
            /** 表示条件（JSON文字列等。任意） */
            String displayCondition
    ) {}

    /**
     * スロット更新リクエスト DTO。
     */
    public record UpdateSignageSlotRequest(
            Integer durationSeconds,
            String displayCondition,
            Boolean isEnabled
    ) {}

    /**
     * スロットレスポンス DTO。
     */
    public record SignageSlotResponse(
            Long id,
            Long screenId,
            SignageSlotType slotType,
            String contentSourceId,
            Integer slotOrder,
            Integer durationSeconds,
            String displayCondition,
            Boolean isEnabled
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * スロットを追加する。slotOrderは既存最大値+1を自動設定する。
     *
     * @param screenId 画面ID
     * @param req      追加リクエスト
     * @return 追加したスロットレスポンス
     */
    @Transactional
    public SignageSlotResponse addSlot(Long screenId, AddSignageSlotRequest req) {
        // 既存スロットの最大slotOrderを取得し、+1を次の順序とする
        int nextOrder = slotRepository.findMaxSlotOrderByScreenId(screenId)
                .map(max -> max + 1)
                .orElse(1);

        SignageSlotEntity entity = SignageSlotEntity.builder()
                .screenId(screenId)
                .slotType(req.slotType())
                .slotOrder(nextOrder)
                // slideDuration フィールドに durationSeconds をマッピング
                .slideDuration(req.durationSeconds())
                // contentConfig フィールドに displayCondition をマッピング
                .contentConfig(req.displayCondition())
                .build();

        SignageSlotEntity saved = slotRepository.save(entity);
        log.info("サイネージスロット追加: id={}, screenId={}, slotOrder={}", saved.getId(), screenId, nextOrder);
        return toResponse(saved);
    }

    /**
     * 画面に紐づくスロット一覧を表示順昇順で取得する。
     *
     * @param screenId 画面ID
     * @return スロットレスポンス一覧
     */
    public List<SignageSlotResponse> listSlots(Long screenId) {
        return slotRepository.findByScreenIdOrderBySlotOrderAsc(screenId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * スロットを更新する。
     *
     * @param id  スロットID
     * @param req 更新リクエスト
     * @return 更新後スロットレスポンス
     */
    @Transactional
    public SignageSlotResponse updateSlot(Long id, UpdateSignageSlotRequest req) {
        SignageSlotEntity entity = findSlotOrThrow(id);

        SignageSlotEntity updated = entity.toBuilder()
                .slideDuration(req.durationSeconds() != null ? req.durationSeconds() : entity.getSlideDuration())
                .contentConfig(req.displayCondition() != null ? req.displayCondition() : entity.getContentConfig())
                .isActive(req.isEnabled() != null ? req.isEnabled() : entity.getIsActive())
                .build();

        SignageSlotEntity saved = slotRepository.save(updated);
        log.info("サイネージスロット更新: id={}", id);
        return toResponse(saved);
    }

    /**
     * スロットを物理削除する。
     *
     * @param id スロットID
     */
    @Transactional
    public void removeSlot(Long id) {
        // 存在確認
        findSlotOrThrow(id);
        slotRepository.deleteById(id);
        log.info("サイネージスロット物理削除: id={}", id);
    }

    /**
     * スロットの並び順を一括更新する。
     * orderedIds の順番がそのまま slotOrder (1始まり) に反映される。
     *
     * @param screenId  画面ID
     * @param orderedIds 並び替え後のスロットID順リスト
     */
    @Transactional
    public void reorderSlots(Long screenId, List<Long> orderedIds) {
        // 画面に紐づく全スロットを取得
        List<SignageSlotEntity> slots = slotRepository.findByScreenIdOrderBySlotOrderAsc(screenId);

        for (int i = 0; i < orderedIds.size(); i++) {
            Long slotId = orderedIds.get(i);
            int newOrder = i + 1;

            // 対象スロットを検索
            SignageSlotEntity target = slots.stream()
                    .filter(s -> s.getId().equals(slotId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_003));

            target.changeOrder(newOrder);
            slotRepository.save(target);
        }

        log.info("サイネージスロット並び替え: screenId={}, slotCount={}", screenId, orderedIds.size());
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでスロットを取得する。見つからない場合は SIGNAGE_003 例外をスローする。
     */
    public SignageSlotEntity findSlotOrThrow(Long id) {
        return slotRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_003));
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private SignageSlotResponse toResponse(SignageSlotEntity e) {
        return new SignageSlotResponse(
                e.getId(),
                e.getScreenId(),
                e.getSlotType(),
                null, // contentSourceId は contentConfig から分離される場合を考慮
                e.getSlotOrder(),
                e.getSlideDuration(),
                e.getContentConfig(),
                e.getIsActive()
        );
    }
}
