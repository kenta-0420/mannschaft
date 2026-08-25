package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.ContentPaymentGateRequest;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Issue #2941: コンテンツゲートの対象スコープと監査情報を固定する red テスト。 */
@ExtendWith(MockitoExtension.class)
class ContentGateIssue2941Test {

    @Mock private ContentPaymentGateRepository gateRepository;
    @Mock private PaymentItemService paymentItemService;
    @InjectMocks private ContentPaymentGateService service;

    @Test
    @DisplayName("AC-1: 管理者でも対象チーム外コンテンツにはゲートを設定できない")
    void 管理者でも対象スコープ外コンテンツは拒否() {
        given(paymentItemService.findByIdOrThrow(20L)).willReturn(item(20L, 1L));
        ContentPaymentGateRequest request = request(999L);

        assertThatThrownBy(() -> service.setTeamContentGates(1L, 100L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-2: 対象チーム内コンテンツはゲート設定に成功する")
    void 対象スコープ内コンテンツは成功() {
        given(paymentItemService.findByIdOrThrow(20L)).willReturn(item(20L, 1L));
        ContentPaymentGateEntity saved = ContentPaymentGateEntity.builder()
                .id(1L).paymentItemId(20L).contentType("POST").contentId(101L)
                .createdBy(100L).createdAt(LocalDateTime.now()).build();
        given(gateRepository.save(any())).willReturn(saved);

        var result = service.setTeamContentGates(1L, 100L, request(101L));

        assertThat(result.gates()).hasSize(1);
        verify(gateRepository).deleteByContentTypeAndContentId("POST", 101L);
    }

    @Test
    @DisplayName("AC-4: 一覧応答は設定対象と操作者の監査情報を保持する")
    void 監査情報を保持する() {
        var gate = ContentPaymentGateEntity.builder().id(1L).paymentItemId(20L)
                .contentType("POST").contentId(101L).createdBy(100L)
                .createdAt(LocalDateTime.of(2026, 8, 25, 10, 0)).build();
        given(paymentItemService.findTeamPaymentItems(1L)).willReturn(List.of(item(20L, 1L)));
        var pageable = PageRequest.of(0, 10);
        given(gateRepository.findByPaymentItemIdIn(List.of(20L), pageable))
                .willReturn(new PageImpl<>(List.of(gate), pageable, 1));

        var response = service.listTeamContentGates(1L, null, pageable).getContent().get(0);

        assertThat(response.getContent().contentId()).isEqualTo(101L);
        assertThat(response.getAudit().createdBy()).isEqualTo(100L);
    }

    private static ContentPaymentGateRequest request(Long contentId) {
        return new ContentPaymentGateRequest("POST", contentId,
                List.of(new ContentPaymentGateRequest.GateEntry(20L, false)));
    }

    private static PaymentItemEntity item(Long id, Long teamId) {
        return PaymentItemEntity.builder().id(id).teamId(teamId).name("会費")
                .type(PaymentItemType.ANNUAL_FEE).amount(BigDecimal.ONE).build();
    }
}
