package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.property.repository.VendorRepository;
import com.mannschaft.app.repairplan.dto.QuoteCardDto;
import com.mannschaft.app.repairplan.dto.QuoteKanbanDto;
import com.mannschaft.app.repairplan.entity.RepairQuoteCard;
import com.mannschaft.app.repairplan.entity.RepairQuoteKanban;
import com.mannschaft.app.repairplan.repository.RepairQuoteCardRepository;
import com.mannschaft.app.repairplan.repository.RepairQuoteKanbanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link RepairPlanQuoteKanbanService} の読み取りマスキング認可ユニットテスト（F08.8 Phase 4 セキュリティ根治）。
 *
 * <p>Docker / Spring コンテキストに依存せず、{@link AccessControlService} をモックしてロール別に
 * {@code resolveIsAdmin} 経由のマスキング挙動を検証する。検証する 3 層:</p>
 * <ul>
 *   <li>管理者（当該 scope の ADMIN/DEPUTY_ADMIN）→ マスクなし（業者名・金額がそのまま）</li>
 *   <li>SYSTEM_ADMIN → マスクなし</li>
 *   <li>一般メンバー（非管理者）→ HIDDEN/締切前で業者名・金額が null、ANONYMIZED で匿名ラベル＋レンジ</li>
 * </ul>
 *
 * <p>根治前は {@code isAdminRole()} が認証さえあれば常に true を返し、一般メンバーにも業者名・金額が
 * 素通しになっていた。本テストは「一般メンバーには確実にマスクされる」ことを Docker 非依存で固定する。</p>
 */
@DisplayName("RepairPlanQuoteKanbanService 読み取りマスキング認可（F08.8 Phase 4）")
@ExtendWith(MockitoExtension.class)
class RepairPlanQuoteKanbanServiceAuthMaskingTest {

    @Mock
    private RepairQuoteKanbanRepository kanbanRepository;

    @Mock
    private RepairQuoteCardRepository cardRepository;

    @Mock
    private VendorRepository vendorRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RepairPlanQuoteKanbanService service;

    private static final Long SCOPE_ID = 700L;
    private static final String SCOPE_TYPE = "ORGANIZATION";
    private static final Long ORG_ID = 700L;
    private static final Long ADMIN_USER = 1L;
    private static final Long MEMBER_USER = 2L;
    private static final Long SYSADMIN_USER = 3L;

    private UUID kanbanId;

    @BeforeEach
    void setUp() {
        kanbanId = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 管理者: マスクなし
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("管理者（ADMIN）: HIDDEN でも業者名・金額がそのまま返る")
    void getKanban_admin_hidden_notMasked() {
        RepairQuoteKanban kanban = kanban("HIDDEN", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);

        when(accessControlService.isSystemAdmin(ADMIN_USER)).thenReturn(false);
        when(accessControlService.isAdminOrAbove(ADMIN_USER, SCOPE_ID, SCOPE_TYPE)).thenReturn(true);

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, ADMIN_USER));

        assertThat(dto.vendorNameSnapshot()).isEqualTo("丸高建設");
        assertThat(dto.amount()).isEqualTo(12_000_000L);
        assertThat(dto.amountRangeLabel()).isNull();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN: HIDDEN でも業者名・金額がそのまま返る")
    void getKanban_systemAdmin_hidden_notMasked() {
        RepairQuoteKanban kanban = kanban("HIDDEN", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);

        when(accessControlService.isSystemAdmin(SYSADMIN_USER)).thenReturn(true);

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, SYSADMIN_USER));

        assertThat(dto.vendorNameSnapshot()).isEqualTo("丸高建設");
        assertThat(dto.amount()).isEqualTo(12_000_000L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 一般メンバー: マスクあり（漏洩遮断の本丸）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("一般メンバー: HIDDEN → 業者名・金額が null にマスクされる")
    void getKanban_member_hidden_masked() {
        RepairQuoteKanban kanban = kanban("HIDDEN", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);
        stubMember();

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, MEMBER_USER));

        assertThat(dto.vendorNameSnapshot()).isNull();
        assertThat(dto.amount()).isNull();
    }

    @Test
    @DisplayName("一般メンバー: ANONYMIZED（締切後）→ 業者A + 金額レンジ、実金額は null")
    void getKanban_member_anonymized_masked() {
        RepairQuoteKanban kanban = kanban("ANONYMIZED", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);
        stubMember();

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, MEMBER_USER));

        assertThat(dto.vendorNameSnapshot()).isEqualTo("業者A");
        assertThat(dto.amount()).isNull();
        assertThat(dto.amountRangeLabel()).isEqualTo("1200〜1300万円台");
    }

    @Test
    @DisplayName("一般メンバー: FULL でも入札締切前は業者名・金額がマスクされる")
    void getKanban_member_fullButBeforeDeadline_masked() {
        RepairQuoteKanban kanban = kanban("FULL", deadlineFuture());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);
        stubMember();

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, MEMBER_USER));

        assertThat(dto.vendorNameSnapshot()).isNull();
        assertThat(dto.amount()).isNull();
    }

    @Test
    @DisplayName("一般メンバー: FULL かつ締切後 → 業者名・金額がそのまま見える")
    void getKanban_member_fullAfterDeadline_notMasked() {
        RepairQuoteKanban kanban = kanban("FULL", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        stubKanbanWithCard(kanban, card);
        stubMember();

        QuoteCardDto dto = firstCard(service.getKanban(kanbanId, ORG_ID, MEMBER_USER));

        assertThat(dto.vendorNameSnapshot()).isEqualTo("丸高建設");
        assertThat(dto.amount()).isEqualTo(12_000_000L);
    }

    @Test
    @DisplayName("一般メンバー: listKanbans でも HIDDEN はマスクされる")
    void listKanbans_member_hidden_masked() {
        RepairQuoteKanban kanban = kanban("HIDDEN", deadlinePast());
        RepairQuoteCard card = card("丸高建設", 12_000_000L);
        when(kanbanRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                SCOPE_TYPE, SCOPE_ID)).thenReturn(List.of(kanban));
        when(cardRepository.findByKanbanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(kanbanId))
                .thenReturn(List.of(card));
        stubMember();

        List<QuoteKanbanDto> result = service.listKanbans(SCOPE_TYPE, SCOPE_ID, ORG_ID, MEMBER_USER);

        assertThat(result).hasSize(1);
        QuoteCardDto dto = result.get(0).cards().get(0);
        assertThat(dto.vendorNameSnapshot()).isNull();
        assertThat(dto.amount()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────────────

    private void stubMember() {
        // 非管理者: SYSTEM_ADMIN でも scope ADMIN でもない
        lenient().when(accessControlService.isSystemAdmin(MEMBER_USER)).thenReturn(false);
        lenient().when(accessControlService.isAdminOrAbove(eq(MEMBER_USER), anyLong(), anyString()))
                .thenReturn(false);
    }

    private void stubKanbanWithCard(RepairQuoteKanban kanban, RepairQuoteCard card) {
        when(kanbanRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(kanbanId, ORG_ID))
                .thenReturn(Optional.of(kanban));
        when(cardRepository.findByKanbanIdAndDeletedAtIsNullOrderByDisplayOrderAsc(kanbanId))
                .thenReturn(List.of(card));
    }

    private RepairQuoteKanban kanban(String visibility, LocalDateTime deadline) {
        RepairQuoteKanban k = RepairQuoteKanban.builder()
                .organizationId(ORG_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .workPackageId(0L)
                .title("相見積もり")
                .bidDeadlineAt(deadline)
                .visibilityToMember(visibility)
                .status("OPEN")
                .createdBy(ADMIN_USER)
                .build();
        k.setId(kanbanId);
        k.setCreatedAt(LocalDateTime.now());
        k.setUpdatedAt(LocalDateTime.now());
        return k;
    }

    private RepairQuoteCard card(String vendorName, Long amount) {
        RepairQuoteCard c = RepairQuoteCard.builder()
                .organizationId(ORG_ID)
                .kanbanId(kanbanId)
                .vendorId(99L)
                .vendorNameSnapshot(vendorName)
                .stage("RECEIVED")
                .amount(amount)
                .complianceCheckStatus("PASSED")
                .displayOrder(1)
                .createdBy(ADMIN_USER)
                .build();
        c.setId(UUID.randomUUID());
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private static LocalDateTime deadlinePast() {
        return LocalDateTime.now().minusDays(1);
    }

    private static LocalDateTime deadlineFuture() {
        return LocalDateTime.now().plusDays(30);
    }

    private static QuoteCardDto firstCard(QuoteKanbanDto dto) {
        return dto.cards().get(0);
    }
}
