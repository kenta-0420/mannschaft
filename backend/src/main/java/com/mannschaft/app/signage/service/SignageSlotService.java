package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.SignageSlotType;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
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
    private final SignageScreenService screenService;
    private final AccessControlService accessControlService;

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
     * @param actor    操作者ユーザーID
     * @param req      追加リクエスト
     * @return 追加したスロットレスポンス
     */
    @Transactional
    public SignageSlotResponse addSlot(Long screenId, Long actor, AddSignageSlotRequest req) {
        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみスロット追加可能
        checkScreenAdmin(screenId, actor);

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
     * <p><b>認可は呼び出し元の責務</b>。サイネージ端末向けの公開表示経路
     * （{@code SignageDisplayController#getDisplayConfig}）はトークン認証済みで
     * ユーザーコンテキストを持たないため、本メソッドには認可を敷かない。
     * 認証ユーザー向けの管理画面入口は {@link #listSlotsForActor} を使うこと。</p>
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
     * 認証ユーザー向けに画面に紐づくスロット一覧を取得する（メンバーシップ必須）。
     *
     * <p>認可根治戦役 Wave7: 認証ユーザー向けの管理画面入口として
     * {@link AccessControlService#checkMembership} でスコープの会員に限定する。
     * 書込系（{@link #addSlot} 等）は ADMIN 限定の {@link #checkScreenAdmin} を使うが、
     * 参照は会員であれば可とする。</p>
     */
    public List<SignageSlotResponse> listSlotsForActor(Long screenId, Long actor) {
        SignageScreenEntity screen = screenService.findScreenOrThrow(screenId);
        accessControlService.checkMembership(actor, screen.getScopeId(), screen.getScopeType());
        return listSlots(screenId);
    }

    /**
     * スロットを更新する。
     *
     * @param id    スロットID
     * @param actor 操作者ユーザーID
     * @param req   更新リクエスト
     * @return 更新後スロットレスポンス
     */
    @Transactional
    public SignageSlotResponse updateSlot(Long id, Long actor, UpdateSignageSlotRequest req) {
        SignageSlotEntity entity = findSlotOrThrow(id);

        // 認可: スロットが属する画面スコープの ADMIN/DEPUTY_ADMIN のみ更新可能
        checkScreenAdmin(entity.getScreenId(), actor);

        // managed entity を直接ミューテートして save することで id=null INSERT を防ぐ。
        // toBuilder().build() では @Builder が BaseEntity の id を引き継がず id=null になるため使用禁止。
        entity.applyUpdate(
                req.durationSeconds(),
                req.displayCondition(),
                req.isEnabled()
        );

        SignageSlotEntity saved = slotRepository.save(entity);
        log.info("サイネージスロット更新: id={}", id);
        return toResponse(saved);
    }

    /**
     * スロットを物理削除する。
     *
     * @param id    スロットID
     * @param actor 操作者ユーザーID
     */
    @Transactional
    public void removeSlot(Long id, Long actor) {
        // 存在確認
        SignageSlotEntity entity = findSlotOrThrow(id);

        // 認可: スロットが属する画面スコープの ADMIN/DEPUTY_ADMIN のみ削除可能
        checkScreenAdmin(entity.getScreenId(), actor);

        slotRepository.deleteById(id);
        log.info("サイネージスロット物理削除: id={}", id);
    }

    /**
     * スロットの並び順を一括更新する。
     * orderedIds の順番がそのまま slotOrder (1始まり) に反映される。
     *
     * @param screenId  画面ID
     * @param actor     操作者ユーザーID
     * @param orderedIds 並び替え後のスロットID順リスト
     */
    @Transactional
    public void reorderSlots(Long screenId, Long actor, List<Long> orderedIds) {
        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみ並び替え可能
        checkScreenAdmin(screenId, actor);

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
     * 指定画面のスコープに対し、操作者が ADMIN/DEPUTY_ADMIN であることを検証する。
     * 画面が存在しなければ SIGNAGE_001、権限が無ければ COMMON_002（403）をスローする。
     *
     * @param screenId 画面ID
     * @param actor    操作者ユーザーID
     */
    private void checkScreenAdmin(Long screenId, Long actor) {
        SignageScreenEntity screen = screenService.findScreenOrThrow(screenId);
        accessControlService.checkAdminOrAbove(actor, screen.getScopeId(), screen.getScopeType());
    }

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
