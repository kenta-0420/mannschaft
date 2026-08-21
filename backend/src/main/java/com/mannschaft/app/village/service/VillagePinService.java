package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PinListResponse;
import com.mannschaft.app.village.dto.PinOrderUpdateRequest;
import com.mannschaft.app.village.dto.PinResponse;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F17.1 B8 — お気に入り村ピン留め管理サービス（設計書 §4.8）。
 *
 * <p>機能:</p>
 * <ul>
 *   <li>ピン一覧取得（sort_order 昇順）</li>
 *   <li>ピン追加（30 件上限・重複 409）</li>
 *   <li>ピン解除</li>
 *   <li>並び替え（現在のピン集合との完全一致を要求）</li>
 *   <li>参加時自動ピン（B3 から呼ばれる、村側 auto_pin_on_join 判定は呼び出し元責任）</li>
 * </ul>
 *
 * <p>{@code @Transactional} は village ドメイン内に閉じる（原則5 準拠）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillagePinService {

    /** ピン上限（設計書 §3.10 / §10）。 */
    public static final int PIN_MAX_LIMIT = 30;

    private final UserVillagePinRepository pinRepository;
    private final VillageRepository villageRepository;
    private final MediaUrlResolver mediaUrlResolver;
    private final VillageAccessGate accessGate;

    // ========================================================================
    // 一覧取得
    // ========================================================================

    /**
     * 自分のピン一覧を取得する（sort_order 昇順）。
     * 村側情報（名前・アイコン）は同期 fetch で join する。
     *
     * @param userId 認証済みユーザーID
     * @return ピン一覧レスポンス
     */
    @Transactional(readOnly = true)
    public PinListResponse listMyPins(Long userId) {
        List<UserVillagePinEntity> pins = pinRepository.findByUserIdOrderBySortOrderAsc(userId);
        Map<UUID, VillageEntity> villageMap = loadVillageMap(pins);
        List<PinResponse> items = pins.stream()
                .map(p -> toResponse(p, villageMap.get(p.getVillageId())))
                .toList();
        return PinListResponse.builder()
                .items(items)
                .count(items.size())
                .maxLimit(PIN_MAX_LIMIT)
                .build();
    }

    // ========================================================================
    // ピン追加
    // ========================================================================

    /**
     * 村をピン留めする。
     *
     * <p>エラーケース:</p>
     * <ul>
     *   <li>404 VILLAGE_NOT_FOUND: 村が存在しない / 削除 / 凍結</li>
     *   <li>409 VILLAGE_PIN_ALREADY_EXISTS: 既にピン済み</li>
     *   <li>422 VILLAGE_PIN_LIMIT_EXCEEDED: 30 件超過</li>
     * </ul>
     *
     * @param userId    認証済みユーザーID
     * @param villageId 対象村 ID
     * @return 作成されたピンのレスポンス
     */
    @Transactional
    public PinResponse pin(Long userId, UUID villageId) {
        VillageEntity village = loadActiveVillage(villageId, userId);

        if (pinRepository.findByUserIdAndVillageId(userId, villageId).isPresent()) {
            throw new BusinessException(VillageErrorCode.VILLAGE_PIN_ALREADY_EXISTS);
        }

        long currentCount = pinRepository.countByUserId(userId);
        if (currentCount >= PIN_MAX_LIMIT) {
            log.info("ピン上限超過: userId={}, current={}", userId, currentCount);
            throw new BusinessException(VillageErrorCode.VILLAGE_PIN_LIMIT_EXCEEDED);
        }

        UserVillagePinEntity entity = UserVillagePinEntity.builder()
                .userId(userId)
                .villageId(villageId)
                .sortOrder(currentCount) // 末尾に追加
                .pinnedAt(LocalDateTime.now())
                .build();

        try {
            UserVillagePinEntity saved = pinRepository.saveAndFlush(entity);
            return toResponse(saved, village);
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE (user_id, village_id) との競合（同一ユーザーの同時 POST）
            log.info("ピン UNIQUE 衝突 (race): userId={}, villageId={}", userId, villageId);
            throw new BusinessException(VillageErrorCode.VILLAGE_PIN_ALREADY_EXISTS, ex);
        }
    }

    // ========================================================================
    // ピン解除
    // ========================================================================

    /**
     * 村のピンを解除する。
     *
     * <p>エラーケース:</p>
     * <ul>
     *   <li>404 VILLAGE_PIN_NOT_FOUND: ピンが存在しない</li>
     * </ul>
     *
     * <p>並び順の詰め直しは行わない（次回の並び替え API で整える運用）。</p>
     *
     * @param userId    認証済みユーザーID
     * @param villageId 対象村 ID
     */
    @Transactional
    public void unpin(Long userId, UUID villageId) {
        UserVillagePinEntity existing = pinRepository.findByUserIdAndVillageId(userId, villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_PIN_NOT_FOUND));
        pinRepository.delete(existing);
    }

    // ========================================================================
    // 並び替え
    // ========================================================================

    /**
     * ピンの並び順を更新する。
     *
     * <p>要件:</p>
     * <ul>
     *   <li>{@code orderedVillageIds} は現在のピン集合と完全一致しなければならない（過不足 422）</li>
     *   <li>先頭から sort_order=0,1,2,... と振り直す</li>
     * </ul>
     *
     * @param userId  認証済みユーザーID
     * @param request 並び替えリクエスト
     * @return 並び替え後の一覧
     */
    @Transactional
    public PinListResponse reorder(Long userId, PinOrderUpdateRequest request) {
        List<UUID> orderedIds = request.orderedVillageIds();
        List<UserVillagePinEntity> currentPins = pinRepository.findByUserIdOrderBySortOrderAsc(userId);

        // 集合一致検証（過不足 / 重複は不一致扱い）
        Set<UUID> orderedSet = new HashSet<>(orderedIds);
        if (orderedSet.size() != orderedIds.size()) {
            // 重複 ID あり
            throw new BusinessException(VillageErrorCode.VILLAGE_PIN_ORDER_MISMATCH);
        }
        Set<UUID> currentSet = new HashSet<>();
        Map<UUID, UserVillagePinEntity> currentMap = new HashMap<>();
        for (UserVillagePinEntity p : currentPins) {
            currentSet.add(p.getVillageId());
            currentMap.put(p.getVillageId(), p);
        }
        if (!orderedSet.equals(currentSet)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_PIN_ORDER_MISMATCH);
        }

        // sort_order を 0,1,2,... と振り直す
        for (int i = 0; i < orderedIds.size(); i++) {
            UserVillagePinEntity p = currentMap.get(orderedIds.get(i));
            p.setSortOrder((long) i);
        }
        pinRepository.saveAll(currentMap.values());
        pinRepository.flush();

        return listMyPins(userId);
    }

    // ========================================================================
    // 参加時自動ピン（B3 から呼ばれる想定）
    // ========================================================================

    /**
     * 村参加時に自動でピン留めする（B3 VillageMembershipService から呼ばれる想定）。
     *
     * <p>本メソッドは「auto_pin_on_join=true」など村側設定の判定後に呼ばれることを想定する。
     * 既にピン済みなら何もしない（冪等）。上限超過時は警告ログのみで例外を投げない（参加処理を阻害しないため）。</p>
     *
     * @param userId    対象ユーザーID
     * @param villageId 対象村 ID
     */
    @Transactional
    public void autoPinOnJoin(Long userId, UUID villageId) {
        if (pinRepository.findByUserIdAndVillageId(userId, villageId).isPresent()) {
            return; // 冪等
        }
        long currentCount = pinRepository.countByUserId(userId);
        if (currentCount >= PIN_MAX_LIMIT) {
            log.info("自動ピン留めスキップ（上限超過）: userId={}, current={}", userId, currentCount);
            return;
        }
        UserVillagePinEntity entity = UserVillagePinEntity.builder()
                .userId(userId)
                .villageId(villageId)
                .sortOrder(currentCount)
                .pinnedAt(LocalDateTime.now())
                .build();
        try {
            pinRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            // 同時参加で race した場合は冪等扱い
            log.info("自動ピン UNIQUE 衝突 (race, 冪等扱い): userId={}, villageId={}", userId, villageId);
        }
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    /**
     * 閲覧可能かつ操作者に可視な村を取得する（判定は {@link VillageAccessGate} に一元化）。
     *
     * <p>従来の実装は不在・削除済み・凍結済みをまとめて 404 に畳んでいたため、
     * 凍結も 404 とする {@link VillageAccessGate#loadReadableVillage} へ委譲して挙動を揃える。
     * 加えて、非公開(UNLISTED)村を非村人が叩いた場合も実在しない村 ID と同一の
     * {@code VILLAGE_NOT_FOUND} となり、村の存在が漏れなくなる。</p>
     */
    private VillageEntity loadActiveVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadReadableVillage(villageId, actorUserId);
    }

    /**
     * ピン群に紐付く村エンティティを 1 クエリで取得して Map 化する。
     * 凍結 / 削除済み村のピンが残っている場合はその村だけ Map に含まれないため、
     * {@link #toResponse(UserVillagePinEntity, VillageEntity)} で null 防御する。
     */
    private Map<UUID, VillageEntity> loadVillageMap(List<UserVillagePinEntity> pins) {
        if (pins.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = pins.stream().map(UserVillagePinEntity::getVillageId).toList();
        Map<UUID, VillageEntity> map = new HashMap<>();
        villageRepository.findAllById(ids).forEach(v -> map.put(v.getId(), v));
        return map;
    }

    private PinResponse toResponse(UserVillagePinEntity pin, VillageEntity village) {
        String name = village != null ? village.getName() : null;
        // DB には生の R2 キーが入る。villageIconUrl は FE が img src に直接渡すため
        // （pages/me/village-pins.vue）、表示用署名付き URL へ解決して返す（生キーは 404）。
        String iconUrl = mediaUrlResolver.resolve(village != null ? village.getIconR2Key() : null);
        return PinResponse.builder()
                .id(pin.getId())
                .villageId(pin.getVillageId())
                .villageName(name)
                .villageIconUrl(iconUrl)
                .sortOrder(pin.getSortOrder() == null ? 0L : pin.getSortOrder())
                .pinnedAt(pin.getPinnedAt())
                .build();
    }
}
