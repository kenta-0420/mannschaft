package com.mannschaft.app.payment;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.ContentPaymentGateRequest;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.service.ContentGateResolverRegistry;
import com.mannschaft.app.payment.service.ContentPaymentGateService;
import com.mannschaft.app.payment.service.PaymentItemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Issue #2941: コンテンツゲートの対象スコープと監査情報を固定する red テスト。 */
@ExtendWith(MockitoExtension.class)
class ContentGateIssue2941Test {

    @Mock private ContentPaymentGateRepository gateRepository;
    @Mock private PaymentItemService paymentItemService;
    @Mock private ContentGateResolverRegistry contentGateResolverRegistry;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private ContentPaymentGateService service;

    @Test
    @DisplayName("AC-1: 管理者でも対象チーム外コンテンツにはゲートを設定できない")
    void 管理者でも対象スコープ外コンテンツは拒否() {
        ContentPaymentGateRequest request = request(999L);
        given(contentGateResolverRegistry.existsInScope("POST", 999L, 1L, null)).willReturn(false);

        assertThatThrownBy(() -> service.setTeamContentGates(1L, 100L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.CONTENT_NOT_FOUND);
        verify(gateRepository, never()).deleteByContentTypeAndContentId("POST", 999L);
    }

    @Test
    @DisplayName("AC-2: 対象チーム内コンテンツはゲート設定に成功する")
    void 対象スコープ内コンテンツは成功() {
        given(paymentItemService.findByIdOrThrow(20L)).willReturn(item(20L, 1L));
        given(contentGateResolverRegistry.existsInScope("POST", 101L, 1L, null)).willReturn(true);
        ContentPaymentGateEntity saved = ContentPaymentGateEntity.builder()
                .id(1L).paymentItemId(20L).contentType("POST").contentId(101L)
                .createdBy(100L).createdAt(LocalDateTime.now()).build();
        given(gateRepository.save(any())).willReturn(saved);

        var result = service.setTeamContentGates(1L, 100L, request(101L));

        assertThat(result.getGates()).hasSize(1);
        verify(gateRepository).deleteByContentTypeAndContentId("POST", 101L);
        verify(auditLogService).record(eq("CONTENT_GATE_UPDATED"), eq(100L), isNull(), eq(1L), isNull(),
                isNull(), isNull(), isNull(), eq("{\"contentType\":\"POST\",\"contentId\":101,\"gateCount\":1}"));
    }

    @Test
    @DisplayName("AC-4: 一覧応答は設定対象と操作者の監査情報を保持する")
    void 監査情報を保持する() {
        var gate = ContentPaymentGateEntity.builder().id(1L).paymentItemId(20L)
                .contentType("POST").contentId(101L).createdBy(100L)
                .createdAt(LocalDateTime.of(2026, 8, 25, 10, 0)).build();
        given(paymentItemService.findTeamPaymentItems(1L)).willReturn(List.of(item(20L, 1L)));
        given(paymentItemService.findByIdOrThrow(20L)).willReturn(item(20L, 1L));
        var pageable = PageRequest.of(0, 10);
        given(gateRepository.findByPaymentItemIdIn(List.of(20L), pageable))
                .willReturn(new PageImpl<>(List.of(gate), pageable, 1));

        var response = service.listTeamContentGates(1L, null, pageable).getContent().get(0);

        assertThat(response.getContent().contentId()).isEqualTo(101L);
        assertThat(response.getAudit().createdBy()).isEqualTo(100L);
    }

    @Test
    @DisplayName("AC-4: 組織ゲート更新は組織スコープと操作者を監査記録する")
    void 組織スコープと操作者を監査記録する() {
        given(paymentItemService.findByIdOrThrow(30L)).willReturn(PaymentItemEntity.builder()
                .id(30L).organizationId(2L).name("item").type(PaymentItemType.ANNUAL_FEE)
                .amount(BigDecimal.ONE).build());
        given(contentGateResolverRegistry.existsInScope("POST", 102L, null, 2L)).willReturn(true);
        given(gateRepository.save(any())).willReturn(ContentPaymentGateEntity.builder().id(2L)
                .paymentItemId(30L).contentType("POST").contentId(102L).createdBy(101L).build());

        service.setOrganizationContentGates(2L, 101L, requestWithPaymentItem(102L, 30L));

        verify(auditLogService).record(eq("CONTENT_GATE_UPDATED"), eq(101L), isNull(), isNull(), eq(2L),
                isNull(), isNull(), isNull(), eq("{\"contentType\":\"POST\",\"contentId\":102,\"gateCount\":1}"));
    }

    @Test
    @DisplayName("AC-4: 空のゲート一覧による解除も対象と操作者を監査記録する")
    void ゲート解除も対象と操作者を監査記録する() {
        given(contentGateResolverRegistry.existsInScope("POST", 103L, 1L, null)).willReturn(true);

        service.setTeamContentGates(1L, 100L, new ContentPaymentGateRequest("POST", 103L, List.of()));

        verify(auditLogService).record(eq("CONTENT_GATE_UPDATED"), eq(100L), isNull(), eq(1L), isNull(),
                isNull(), isNull(), isNull(), eq("{\"contentType\":\"POST\",\"contentId\":103,\"gateCount\":0}"));
    }

    @Test
    @DisplayName("AC-4: 入力検証失敗時は監査記録しない")
    void 入力検証失敗時は監査記録しない() {
        given(contentGateResolverRegistry.existsInScope("POST", 104L, 1L, null)).willReturn(true);
        given(paymentItemService.findByIdOrThrow(20L))
                .willThrow(new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));

        assertThatThrownBy(() -> service.setTeamContentGates(1L, 100L, request(104L)))
                .isInstanceOf(BusinessException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AC-4: ゲート保存失敗時は監査記録しない")
    void ゲート保存失敗時は監査記録しない() {
        given(paymentItemService.findByIdOrThrow(20L)).willReturn(item(20L, 1L));
        given(contentGateResolverRegistry.existsInScope("POST", 105L, 1L, null)).willReturn(true);
        given(gateRepository.save(any())).willThrow(new RuntimeException("save failed"));

        assertThatThrownBy(() -> service.setTeamContentGates(1L, 100L, request(105L)))
                .isInstanceOf(RuntimeException.class);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static ContentPaymentGateRequest request(Long contentId) {
        return requestWithPaymentItem(contentId, 20L);
    }

    private static ContentPaymentGateRequest requestWithPaymentItem(Long contentId, Long paymentItemId) {
        return new ContentPaymentGateRequest("POST", contentId,
                List.of(new ContentPaymentGateRequest.GateEntry(paymentItemId, false)));
    }

    private static PaymentItemEntity item(Long id, Long teamId) {
        return PaymentItemEntity.builder().id(id).teamId(teamId).name("会費")
                .type(PaymentItemType.ANNUAL_FEE).amount(BigDecimal.ONE).build();
    }
}
