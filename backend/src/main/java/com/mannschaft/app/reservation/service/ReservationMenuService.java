package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateReservationMenuRequest;
import com.mannschaft.app.reservation.dto.ReservationMenuDeleteResponse;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationMenuRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 予約メニューサービス（F03.4.1 機能E）。
 *
 * <p>CRUD・上限/所要時間/提供可否バリデーション・view ゲート委譲を担う（§5）。
 * 会員向け GET の閲覧可否は {@link ReservationViewAccessGuard#assertCanView} に集約
 * （予約作成・グリッドと同一述語。独自の可視性述語を新設しない — §6・
 * {@code feedback_visibility_bypass_f00_audit}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationMenuService {

    /** F03.4.1 §3: 1 チームあたりのメニュー上限（論理削除済みは数えない）。 */
    public static final long MAX_MENUS_PER_TEAM = 20L;

    /** F03.4.1 §3: 所要時間の下限/上限（30分の倍数・30〜480 = 8時間16枠）。 */
    private static final int MIN_DURATION_MINUTES = 30;
    private static final int MAX_DURATION_MINUTES = 480;
    private static final int DURATION_UNIT_MINUTES = 30;

    /** F03.4.1 §3: display_order の許可範囲（1〜20・チーム内）。 */
    private static final int MAX_DISPLAY_ORDER = 20;

    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationViewAccessGuard viewAccessGuard;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * メニュー一覧を取得する（E-7 可視性）。
     *
     * <p>view ゲート通過後、ADMIN+ なら無効（is_active=FALSE）も含む全件、
     * 会員/公開ユーザーは有効メニューのみ（ロールで自動決定・パラメータ改竄面を作らない — §4）。
     * {@code lineIds} は一括取得（N+1 回避）し、参照先ラインが削除済みの行は内部フィルタで
     * 除外して返す（論理削除ラインの ID を露出させない — §5）。</p>
     *
     * @param teamId チームID
     * @param userId 閲覧ユーザーID
     * @return メニューレスポンスリスト（display_order, created_at, id の三段ソート）
     */
    public List<ReservationMenuResponse> listMenus(Long teamId, Long userId) {
        viewAccessGuard.assertCanView(teamId, userId);

        List<ReservationMenuEntity> menus =
                menuRepository.findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(teamId);
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, teamId, "TEAM");
        if (!isAdmin) {
            menus = menus.stream().filter(ReservationMenuEntity::getIsActive).toList();
        }
        if (menus.isEmpty()) {
            return List.of();
        }

        Set<Long> activeLineIds = fetchActiveLineIds(teamId);
        List<UUID> menuIds = menus.stream().map(ReservationMenuEntity::getId).toList();
        Map<UUID, List<Long>> lineIdsByMenu = menuLineRepository.findByMenuIdIn(menuIds).stream()
                .filter(row -> activeLineIds.contains(row.getLineId()))
                .collect(Collectors.groupingBy(
                        ReservationMenuLineEntity::getMenuId,
                        Collectors.mapping(ReservationMenuLineEntity::getLineId, Collectors.toList())));

        return menus.stream()
                .map(menu -> toResponse(menu, lineIdsByMenu.getOrDefault(menu.getId(), List.of())))
                .toList();
    }

    /**
     * メニューを作成する（E-1〜E-5・§5 主要フロー）。
     *
     * <ol>
     *   <li>上限 20 件（論理削除済みは数えない）→ 400 = RESERVATION_033</li>
     *   <li>所要時間 30 の倍数・30〜480 → 400 = RESERVATION_034</li>
     *   <li>lineIds 指定時: 各 ID が当該チームの active ラインに存在 → 400 = RESERVATION_035
     *       （他チームのライン ID も同コード = 存在秘匿）</li>
     *   <li>INSERT ＋ lineIds ぶんの提供可否行 INSERT（同一 tx）</li>
     * </ol>
     *
     * @param teamId      チームID
     * @param request     作成リクエスト
     * @param actorUserId 操作ユーザーID（created_by・監査ログ）
     * @return 作成されたメニュー
     */
    @Transactional
    public ReservationMenuResponse createMenu(
            Long teamId, CreateReservationMenuRequest request, Long actorUserId) {
        if (menuRepository.countByTeamIdAndDeletedAtIsNull(teamId) >= MAX_MENUS_PER_TEAM) {
            throw new BusinessException(ReservationErrorCode.MENU_LIMIT_EXCEEDED);
        }
        validateDuration(request.getDurationMinutes());

        // 「省略=全ライン提供可」の既定は final DTO では表現できないため null→空リスト正規化（§4）。
        List<Long> lineIds = normalizeLineIds(request.getLineIds(), teamId);

        // displayOrder 省略時は未削除行の MAX(display_order)+1（歯抜け衝突回避・§4。
        // 既存 0 件なら 1。MAX が既に 20 なら 20 のまま重複許容 = 三段ソートで安定）。
        int displayOrder = request.getDisplayOrder() != null
                ? request.getDisplayOrder()
                : nextDisplayOrder(teamId);

        ReservationMenuEntity entity = ReservationMenuEntity.builder()
                .teamId(teamId)
                .name(sanitizeRequiredName(request.getName()))
                .durationMinutes(request.getDurationMinutes())
                .price(request.getPrice())
                .description(HtmlSanitizer.sanitizePlainText(request.getDescription()))
                .displayOrder(displayOrder)
                .createdBy(actorUserId)
                .build();
        ReservationMenuEntity saved = menuRepository.save(entity);

        if (!lineIds.isEmpty()) {
            menuLineRepository.saveAll(toMenuLineEntities(saved.getId(), lineIds));
        }

        log.info("予約メニュー作成: teamId={}, menuId={}, name={}, duration={}, lineIds={}",
                teamId, saved.getId(), saved.getName(), saved.getDurationMinutes(), lineIds);
        recordAudit("RESERVATION_MENU_CREATED", actorUserId, teamId, saved.getId());
        return toResponse(saved, lineIds);
    }

    /**
     * メニューを部分更新する（E-6・E-9。null/未指定 = 据え置き）。
     *
     * <p>{@code lineIds}: null = 据え置き / 空配列 = 全ライン提供可へ戻す / 列挙 = 全置換
     * （差分 API にしない — 行数最大 20 で全置換のコストは無視できる・§4）。
     * {@code clearPrice=true} で price を null（料金非表示）へ戻す（このとき price は無視）。</p>
     *
     * @param teamId      チームID
     * @param menuId      メニューID
     * @param request     更新リクエスト
     * @param actorUserId 操作ユーザーID（監査ログ）
     * @return 更新後のメニュー
     */
    @Transactional
    public ReservationMenuResponse updateMenu(
            Long teamId, UUID menuId, UpdateReservationMenuRequest request, Long actorUserId) {
        ReservationMenuEntity entity = findMenuOrThrow(teamId, menuId);

        if (request.getName() != null) {
            entity.changeName(sanitizeRequiredName(request.getName()));
        }
        if (request.getDurationMinutes() != null) {
            // 将来枠に既存予約グループがあっても変更可（新規予約から適用・遡及なし原則 §4）。
            validateDuration(request.getDurationMinutes());
            entity.changeDurationMinutes(request.getDurationMinutes());
        }
        if (Boolean.TRUE.equals(request.getClearPrice())) {
            // clearPrice=true のとき price は無視（null 据え置きと null 設定の区別 — §4）。
            entity.clearPrice();
        } else if (request.getPrice() != null) {
            entity.changePrice(request.getPrice());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(HtmlSanitizer.sanitizePlainText(request.getDescription()));
        }
        if (request.getDisplayOrder() != null) {
            entity.changeDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsActive() != null) {
            if (request.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }

        List<Long> resultLineIds;
        if (request.getLineIds() != null) {
            // 全置換: 空配列 = 全ライン提供可へ戻す（行 0 件）。列挙 = 検証のうえ削除→挿入。
            List<Long> newLineIds = normalizeLineIds(request.getLineIds(), teamId);
            menuLineRepository.deleteByMenuId(menuId);
            // Hibernate は flush 時に INSERT を DELETE より先に並べ替えるため、既存行と重複する
            // (menu_id, line_id) を含む再列挙で複合 PK 衝突が起きる。DELETE を即時 flush して
            // 置換順序を DB 上でも確定させる（検分指摘 #2160-1）。
            menuLineRepository.flush();
            if (!newLineIds.isEmpty()) {
                menuLineRepository.saveAll(toMenuLineEntities(menuId, newLineIds));
            }
            resultLineIds = newLineIds;
        } else {
            // 据え置き: 現在の提供可否行を再読（削除済みライン内部フィルタ付き）。
            Set<Long> activeLineIds = fetchActiveLineIds(teamId);
            resultLineIds = menuLineRepository.findByMenuId(menuId).stream()
                    .map(ReservationMenuLineEntity::getLineId)
                    .filter(activeLineIds::contains)
                    .sorted()
                    .toList();
        }

        ReservationMenuEntity saved = menuRepository.save(entity);
        log.info("予約メニュー更新: teamId={}, menuId={}", teamId, menuId);
        recordAudit("RESERVATION_MENU_UPDATED", actorUserId, teamId, menuId);
        return toResponse(saved, resultLineIds);
    }

    /**
     * メニューを論理削除する（E-8 足場・E-9）。
     *
     * <p>未来の予約グループが参照していても削除は許可（メニューは表示属性であり、削除しても
     * グループの枠・時間は既に確保済みで業務が壊れない — §4）。{@code reservation_menu_lines}
     * 行は物理削除しない（menu 行が論理削除で不可視になるため実害なし。物理削除時は CASCADE で追従）。
     * 予約詳細のメニュー名は {@code findByIdIncludingDeleted} で解決する（§3 備考）。</p>
     *
     * @param teamId      チームID
     * @param menuId      メニューID
     * @param actorUserId 操作ユーザーID（監査ログ）
     * @return 削除結果（id・deletedAt）
     */
    @Transactional
    public ReservationMenuDeleteResponse deleteMenu(Long teamId, UUID menuId, Long actorUserId) {
        ReservationMenuEntity entity = findMenuOrThrow(teamId, menuId);
        entity.softDelete();
        ReservationMenuEntity saved = menuRepository.save(entity);
        log.info("予約メニュー削除（論理削除）: teamId={}, menuId={}", teamId, menuId);
        recordAudit("RESERVATION_MENU_DELETED", actorUserId, teamId, menuId);
        return ReservationMenuDeleteResponse.builder()
                .id(saved.getId())
                .deletedAt(saved.getDeletedAt())
                .build();
    }

    // ── 内部ヘルパー ────────────────────────────────────────────

    /**
     * name を HTML タグ除去（§6 XSS 対策）し、<b>サニタイズ後に空になる入力を 400 で拒否</b>する。
     *
     * <p>{@code <b></b>} のようなタグのみの入力は {@code @NotBlank}/{@code @Size}（サニタイズ前の
     * Bean Validation）を迂回して空文字が保存される穴になるため、サニタイズ後にも空チェックを行う
     * （COMMON_001・検分指摘 #2160-3）。</p>
     */
    private String sanitizeRequiredName(String name) {
        String sanitized = HtmlSanitizer.sanitizePlainText(name);
        if (sanitized == null || sanitized.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return sanitized;
    }

    /**
     * 所要時間が 30 の倍数・30〜480 かを検証する（E-3・400 = RESERVATION_034）。
     */
    private void validateDuration(Integer durationMinutes) {
        if (durationMinutes == null
                || durationMinutes % DURATION_UNIT_MINUTES != 0
                || durationMinutes < MIN_DURATION_MINUTES
                || durationMinutes > MAX_DURATION_MINUTES) {
            throw new BusinessException(ReservationErrorCode.INVALID_MENU_DURATION);
        }
    }

    /**
     * lineIds を正規化（null→空リスト・重複除去・昇順）し、各 ID が当該チームの active ライン
     * （{@code deleted_at IS NULL}）に存在することを検証する（E-5・400 = RESERVATION_035）。
     * 他チームのライン ID も同コード（存在秘匿）。
     */
    private List<Long> normalizeLineIds(List<Long> lineIds, Long teamId) {
        if (lineIds == null || lineIds.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = lineIds.stream().distinct().sorted().toList();
        Set<Long> activeLineIds = fetchActiveLineIds(teamId);
        if (!activeLineIds.containsAll(normalized)) {
            throw new BusinessException(ReservationErrorCode.MENU_LINE_IDS_INVALID);
        }
        return normalized;
    }

    /**
     * チームの active ライン（論理削除除外・{@code @SQLRestriction} 適用）の ID 集合を返す。
     */
    private Set<Long> fetchActiveLineIds(Long teamId) {
        return lineRepository.findByTeamIdOrderByDisplayOrderAsc(teamId).stream()
                .map(ReservationLineEntity::getId)
                .collect(Collectors.toSet());
    }

    /**
     * displayOrder 省略時の既定値: 未削除行の MAX(display_order)+1（上限 20 で頭打ち・§4）。
     */
    private int nextDisplayOrder(Long teamId) {
        Integer max = menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(teamId);
        if (max == null) {
            return 1;
        }
        return Math.min(max + 1, MAX_DISPLAY_ORDER);
    }

    private List<ReservationMenuLineEntity> toMenuLineEntities(UUID menuId, List<Long> lineIds) {
        return lineIds.stream()
                .map(lineId -> ReservationMenuLineEntity.builder()
                        .menuId(menuId)
                        .lineId(lineId)
                        .build())
                .toList();
    }

    private ReservationMenuResponse toResponse(ReservationMenuEntity entity, List<Long> lineIds) {
        return ReservationMenuResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .durationMinutes(entity.getDurationMinutes())
                .requiredSlotCount(entity.getRequiredSlotCount())
                .price(entity.getPrice())
                .description(entity.getDescription())
                .displayOrder(entity.getDisplayOrder())
                .isActive(entity.getIsActive())
                .lineIds(lineIds)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 監査ログを記録する（§6。{@code AuditLogService.record} は @Async・失敗してもメイン処理を止めない）。
     */
    private void recordAudit(String eventType, Long actorUserId, Long teamId, UUID menuId) {
        auditLogService.record(eventType, actorUserId, null, teamId, null,
                null, null, null, "{\"menuId\":\"" + menuId + "\"}");
    }

    /**
     * メニューを取得する。存在しない/他チームの場合は 404（RESERVATION_032・IDOR 秘匿）。
     */
    private ReservationMenuEntity findMenuOrThrow(Long teamId, UUID menuId) {
        return menuRepository.findByIdAndTeamId(menuId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.MENU_NOT_FOUND));
    }
}
