package com.mannschaft.app.ticket;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ticket.dto.CreateTicketProductRequest;
import com.mannschaft.app.ticket.dto.TicketProductResponse;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import com.mannschaft.app.ticket.service.StripeTicketService;
import com.mannschaft.app.ticket.service.TicketProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TicketProductService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TicketProductService 単体テスト")
class TicketProductServiceTest {

    @Mock private TicketProductRepository productRepository;
    @Mock private StripeTicketService stripeTicketService;
    @Mock private TicketMapper ticketMapper;

    @InjectMocks
    private TicketProductService service;

    private static final Long TEAM_ID = 1L;
    private static final Long PRODUCT_ID = 10L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("createProduct")
    class CreateProduct {

        @Test
        @DisplayName("異常系: 商品数上限超過")
        void 商品数上限超過() {
            given(productRepository.countByTeamId(TEAM_ID)).willReturn(30L);

            CreateTicketProductRequest request = new CreateTicketProductRequest(
                    "テスト", null, 10, 5000, null, 90, true, null);

            assertThatThrownBy(() -> service.createProduct(TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.PRODUCT_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("正常系: オンライン購入可能商品でStripe連携される")
        void 正常作成_Stripe連携() {
            given(productRepository.countByTeamId(TEAM_ID)).willReturn(0L);
            TicketProductEntity saved = TicketProductEntity.builder()
                    .teamId(TEAM_ID).name("テスト").totalTickets(10)
                    .price(5000).isOnlinePurchasable(true).build();
            given(productRepository.save(any())).willReturn(saved);
            given(stripeTicketService.createStripeProduct(saved))
                    .willReturn(new StripeTicketService.StripeProductResult("prod_xxx", "price_xxx"));
            given(ticketMapper.toProductResponse(any())).willReturn(null);

            CreateTicketProductRequest request = new CreateTicketProductRequest(
                    "テスト", null, 10, 5000, null, 90, true, null);

            service.createProduct(TEAM_ID, USER_ID, request);

            verify(stripeTicketService).createStripeProduct(any());
        }
    }

    @Nested
    @DisplayName("deleteProduct")
    class DeleteProduct {

        @Test
        @DisplayName("異常系: 商品が見つからない")
        void 商品不存在() {
            given(productRepository.findByIdAndTeamId(PRODUCT_ID, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteProduct(TEAM_ID, PRODUCT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TicketErrorCode.PRODUCT_NOT_FOUND);
        }
    }
}
