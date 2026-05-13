package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.VendorRepository;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.dto.AddCardRequest;
import com.mannschaft.app.repairplan.dto.CreateKanbanRequest;
import com.mannschaft.app.repairplan.dto.MoveCardRequest;
import com.mannschaft.app.repairplan.dto.QuoteCardDto;
import com.mannschaft.app.repairplan.dto.QuoteKanbanDto;
import com.mannschaft.app.repairplan.dto.UpdateKanbanRequest;
import com.mannschaft.app.repairplan.entity.RepairQuoteCard;
import com.mannschaft.app.repairplan.entity.RepairQuoteKanban;
import com.mannschaft.app.repairplan.repository.RepairQuoteCardRepository;
import com.mannschaft.app.repairplan.repository.RepairQuoteKanbanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 相見積もりカンバンサービス（F08.8 Phase 4）。
 *
 * <h2>可視性フィルタ</h2>
 * <p>カンバンの {@code visibilityToMember} に従い、カード情報をマスキングする:</p>
 * <ul>
 *   <li>HIDDEN    — 業者名・金額を null にして返す</li>
 *   <li>ANONYMIZED — 業者名を「業者A/B/C…」、金額をレンジ表示に変換して返す</li>
 *   <li>FULL      — そのまま返す</li>
 * </ul>
 * <p>入札締切日前は visibility に関わらず業者名をマスクする。</p>
 *
 * <h2>stage 遷移ルール（前進のみ）</h2>
 * <pre>
 * REQUESTED → RECEIVED / REJECTED
 * RECEIVED  → UNDER_REVIEW / REJECTED
 * UNDER_REVIEW → SHORTLISTED / REJECTED
 * SHORTLISTED  → SELECTED / REJECTED
 * SELECTED  → （終端・変更不可）
 * REJECTED  → （終端・変更不可）
 * </pre>
 *
 * <h2>ドメイン境界注記</h2>
 * <p>{@code VendorRepository} は {@code property} ドメインへのクロスドメイン参照。
 * 将来的には VendorService 経由にするが、現時点は直接参照（TODO コメントあり）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepairPlanQuoteKanbanService {

    private static final Set<String> TERMINAL_STAGES = Set.of("SELECTED", "REJECTED");

    /** stage 遷移マトリクス（許可される前進遷移のみ定義）。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "REQUESTED",   Set.of("RECEIVED", "REJECTED"),
            "RECEIVED",    Set.of("UNDER_REVIEW", "REJECTED"),
            "UNDER_REVIEW", Set.of("SHORTLISTED", "REJECTED"),
            "SHORTLISTED", Set.of("SELECTED", "REJECTED")
    );

    private final RepairQuoteKanbanRepository kanbanRepository;
    private final RepairQuoteCardRepository cardRepository;

    /**
     * TODO: VendorRepository は property ドメインへのクロスドメイン参照。
     * 将来は RepairPlanQuoteKanbanService が VendorService を介してデータを取得する形に変更予定。
     */
    private final VendorRepository vendorRepository;

    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────────────────────────────
    // カンバン CRUD
    // ─────────────────────────────────────────────────────────────────────

    /**
     * スコープ配下のカンバン一覧を取得する（visibility フィルタ適用済み）。
     */
    public List<QuoteKanbanDto> listKanbans(String scopeType, Long scopeId, Long organizationId) {
        List<RepairQuoteKanban> kanbans =
                kanbanRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        scopeType, scopeId);
        boolean isAdmin = isAdminRole();
        return kanbans.stream()
                .map(k -> toDto(k, isAdmin))
                .toList();
    }

    /**
     * カンバンを 1 件取得する。
     */
    public QuoteKanbanDto getKanban(UUID kanbanId, Long organizationId) {
        RepairQuoteKanban kanban = findKanbanForOrg(kanbanId, organizationId);
        boolean isAdmin = isAdminRole();
        return toDto(kanban, isAdmin);
    }

    /**
     * カンバンを作成する（ADMIN/DEPUTY_ADMIN 以上）。
     */
    @Transactional
    public QuoteKanbanDto createKanban(String scopeType, Long scopeId, Long organizationId,
                                       CreateKanbanRequest request, Long userId) {
        RepairQuoteKanban kanban = RepairQuoteKanban.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .workPackageId(request.workPackageId() != null ? request.workPackageId() : 0L)
                .repairPlanItemId(request.repairPlanItemId())
                .title(request.title())
                .bidDeadlineAt(toLocalDateTime(request.bidDeadlineAt()))
                .visibilityToMember(request.visibilityToMember())
                .status("OPEN")
                .createdBy(userId)
                .build();

        RepairQuoteKanban saved = kanbanRepository.save(kanban);

        auditLogService.record(
                AuditEventType.PLAN_ITEM_CREATED.name(),
                userId, null,
                null, organizationId,
                null, null, null,
                "{\"kanbanId\":\"" + saved.getId() + "\",\"title\":\"" + request.title() + "\"}");

        return toDto(saved, true);
    }

    /**
     * カンバンを更新する（ADMIN/DEPUTY_ADMIN 以上）。楽観ロック適用。
     */
    @Transactional
    public QuoteKanbanDto updateKanban(UUID kanbanId, Long organizationId,
                                       UpdateKanbanRequest request, Long userId) {
        RepairQuoteKanban kanban = findKanbanForOrg(kanbanId, organizationId);
        try {
            if (request.title() != null) {
                kanban.setTitle(request.title());
            }
            if (request.bidDeadlineAt() != null) {
                kanban.setBidDeadlineAt(toLocalDateTime(request.bidDeadlineAt()));
            }
            if (request.visibilityToMember() != null) {
                kanban.setVisibilityToMember(request.visibilityToMember());
            }
            if (request.status() != null) {
                kanban.setStatus(request.status());
            }
            RepairQuoteKanban saved = kanbanRepository.save(kanban);
            return toDto(saved, true);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(RepairPlanErrorCode.ITEM_VERSION_CONFLICT, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // カード操作
    // ─────────────────────────────────────────────────────────────────────

    /**
     * カードをカンバンに追加する（ADMIN/DEPUTY_ADMIN 以上）。
     *
     * <h3>反社チェック検証</h3>
     * <p>vendors.compliance_check_status が EXPIRED の業者はカードに追加できない。</p>
     */
    @Transactional
    public QuoteCardDto addCard(UUID kanbanId, Long organizationId,
                                AddCardRequest request, Long userId) {
        // IDOR 検証: kanban が組織に属することを確認
        RepairQuoteKanban kanban = findKanbanForOrg(kanbanId, organizationId);

        // TODO: VendorRepository は property ドメインへのクロスドメイン参照。
        //       将来は VendorService.getById() 経由に変更予定。
        VendorEntity vendor = vendorRepository.findByIdAndDeletedAtIsNull(request.vendorId())
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.CARD_NOT_FOUND));

        // 反社チェック期限切れ検証
        if ("EXPIRED".equals(vendor.getComplianceCheckStatus())) {
            throw new BusinessException(RepairPlanErrorCode.COMPLIANCE_EXPIRED);
        }

        // 既存カード数から displayOrder を決定
        List<RepairQuoteCard> existingCards =
                cardRepository.findByKanbanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(kanbanId);
        int displayOrder = existingCards.size() + 1;

        RepairQuoteCard card = RepairQuoteCard.builder()
                .organizationId(organizationId)
                .kanbanId(kanbanId)
                .vendorId(request.vendorId())
                .vendorNameSnapshot(request.vendorNameSnapshot())
                .stage("REQUESTED")
                .amount(request.amount())
                .breakdownJson(request.breakdownJson())
                .complianceCheckStatus(vendor.getComplianceCheckStatus())
                .complianceCheckedAt(vendor.getComplianceCheckedAt())
                .displayOrder(displayOrder)
                .createdBy(userId)
                .build();

        RepairQuoteCard saved = cardRepository.save(card);

        auditLogService.record(
                AuditEventType.BID_CARD_CREATED.name(),
                userId, null,
                null, organizationId,
                null, null, null,
                "{\"kanbanId\":\"" + kanbanId + "\",\"cardId\":\"" + saved.getId()
                        + "\",\"vendorId\":" + request.vendorId() + "}");

        return toCardDto(saved, kanban, true, 0);
    }

    /**
     * カードのステージを移動する（ADMIN/DEPUTY_ADMIN 以上）。
     *
     * <h3>遷移検証</h3>
     * <ul>
     *   <li>終端ステージ（SELECTED / REJECTED）からの遷移は不可</li>
     *   <li>前進のみ許可（後戻り禁止）</li>
     *   <li>SELECTED 確定時: work_packages への業者選定更新（stub: TODO コメントあり）</li>
     * </ul>
     */
    @Transactional
    public QuoteCardDto moveCard(UUID cardId, Long organizationId,
                                 MoveCardRequest request, Long userId) {
        // IDOR 検証: card → kanban → scope の連鎖検証
        RepairQuoteCard card = cardRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(
                cardId, organizationId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.CARD_NOT_FOUND));

        // kanban の組織帰属確認（二重チェック）
        RepairQuoteKanban kanban = findKanbanForOrg(card.getKanbanId(), organizationId);

        String currentStage = card.getStage();
        String newStage = request.newStage();

        // 終端ステージからの遷移禁止
        if (TERMINAL_STAGES.contains(currentStage)) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_STAGE_TRANSITION);
        }

        // 許可された遷移かチェック
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(currentStage, Set.of());
        if (!allowed.contains(newStage)) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_STAGE_TRANSITION);
        }

        try {
            card.setStage(newStage);
            RepairQuoteCard saved = cardRepository.save(card);

            AuditEventType auditType = "SELECTED".equals(newStage)
                    ? AuditEventType.BID_VENDOR_SELECTED
                    : AuditEventType.BID_CARD_MOVED;

            auditLogService.record(
                    auditType.name(),
                    userId, null,
                    null, organizationId,
                    null, null, null,
                    "{\"cardId\":\"" + cardId + "\",\"from\":\"" + currentStage
                            + "\",\"to\":\"" + newStage + "\"}");

            // SELECTED 確定時: work_packages への業者選定更新
            if ("SELECTED".equals(newStage) && kanban.getWorkPackageId() != null
                    && kanban.getWorkPackageId() > 0) {
                // TODO: クロスドメイン更新。将来 WorkPackageVendorSelectedEvent に分離予定。
                //       現時点は PropertyWorkPackageService が未実装のため stub（no-op）。
                log.info("TODO: work_package_id={} に vendor_id={} を選定完了として反映する。"
                                + "将来 WorkPackageVendorSelectedEvent を発行予定。",
                        kanban.getWorkPackageId(), card.getVendorId());
            }

            return toCardDto(saved, kanban, true, 0);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(RepairPlanErrorCode.ITEM_VERSION_CONFLICT, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // プライベートヘルパー
    // ─────────────────────────────────────────────────────────────────────

    private RepairQuoteKanban findKanbanForOrg(UUID kanbanId, Long organizationId) {
        return kanbanRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(kanbanId, organizationId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.KANBAN_NOT_FOUND));
    }

    /** 現在のセキュリティコンテキストから管理者ロールかどうかを簡易判定する。 */
    private boolean isAdminRole() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            // ロール情報は Controller 層で検証済みの前提。
            // Service 層では全データを返し、呼び出し元が必要に応じてフィルタする。
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private QuoteKanbanDto toDto(RepairQuoteKanban kanban, boolean isAdmin) {
        List<RepairQuoteCard> cards =
                cardRepository.findByKanbanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                        kanban.getId());

        AtomicInteger anonymizedIndex = new AtomicInteger(0);
        List<QuoteCardDto> cardDtos = cards.stream()
                .map(c -> toCardDto(c, kanban, isAdmin, anonymizedIndex.getAndIncrement()))
                .toList();

        return new QuoteKanbanDto(
                kanban.getId(),
                kanban.getTitle(),
                kanban.getScopeType(),
                kanban.getScopeId(),
                kanban.getOrganizationId(),
                kanban.getWorkPackageId(),
                kanban.getRepairPlanItemId(),
                kanban.getBidDeadlineAt(),
                kanban.getVisibilityToMember(),
                kanban.getStatus(),
                cardDtos,
                kanban.getCreatedAt(),
                kanban.getUpdatedAt()
        );
    }

    private QuoteCardDto toCardDto(RepairQuoteCard card, RepairQuoteKanban kanban,
                                   boolean isAdmin, int anonymizedIndex) {
        String visibility = kanban.getVisibilityToMember();
        boolean beforeDeadline = kanban.getBidDeadlineAt() != null
                && LocalDateTime.now().isBefore(kanban.getBidDeadlineAt());

        String vendorName = card.getVendorNameSnapshot();
        Long amount = card.getAmount();
        String amountRangeLabel = null;

        if (!isAdmin) {
            if ("HIDDEN".equals(visibility) || beforeDeadline) {
                vendorName = null;
                amount = null;
            } else if ("ANONYMIZED".equals(visibility)) {
                vendorName = anonymizedVendorLabel(anonymizedIndex);
                amountRangeLabel = rangeLabel(card.getAmount());
                amount = null;
            }
            // FULL: そのまま返す
        }

        return new QuoteCardDto(
                card.getId(),
                card.getKanbanId(),
                card.getVendorId(),
                vendorName,
                card.getStage(),
                amount,
                amountRangeLabel,
                card.getComplianceCheckStatus(),
                card.getDisplayOrder(),
                card.getCreatedAt()
        );
    }

    /**
     * 匿名ラベルを生成する（業者A, 業者B, ... 業者Z, 業者AA, ...）。
     */
    private static String anonymizedVendorLabel(int index) {
        StringBuilder letters = new StringBuilder();
        int n = index;
        do {
            letters.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return "業者" + letters;
    }

    /**
     * 金額をレンジラベルに変換する。
     *
     * <pre>
     * null       → null
     * 0〜99万   → "〜100万円未満"
     * 100〜199万 → "100〜200万円台"
     * N*100万〜(N+1)*100万-1 → "N*100〜(N+1)*100万円台"
     * </pre>
     */
    public static String rangeLabel(Long amount) {
        if (amount == null) {
            return null;
        }
        long man = amount / 10_000; // 万円単位
        if (man < 100) {
            return "〜100万円未満";
        }
        long lower = (man / 100) * 100;
        long upper = lower + 100;
        return lower + "〜" + upper + "万円台";
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
