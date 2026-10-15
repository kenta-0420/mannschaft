package com.mannschaft.app.tournament.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeeItem;
import com.mannschaft.app.tournament.fee.dto.MyTournamentFeesResponse;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutRequest;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeCheckoutResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TournamentFeeCheckoutController} 契約テスト（F08.7.1 Connect 決済）。
 *
 * <p>{@code @WebMvcTest + @EnableMethodSecurity} 非互換を回避するため
 * {@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} を用いる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFeeCheckoutController 契約テスト")
class TournamentFeeCheckoutControllerTest {

    private static final Long USER_ID = 100L;

    @Mock
    private TournamentFeePaymentService tournamentFeePaymentService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        TournamentFeeCheckoutController controller =
                new TournamentFeeCheckoutController(tournamentFeePaymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("GET /my: 200 OK / fees リスト返却")
    void getMyTournamentFees_200_returnsFeesList() throws Exception {
        UUID feeId = UUID.randomUUID();
        MyTournamentFeeItem item = new MyTournamentFeeItem(
                feeId, 1L, "テスト大会", null, null,
                "2026 春季 参加費", 300L, 5000, 0, 5000,
                null, false, null);
        given(tournamentFeePaymentService.getMyTournamentFees(USER_ID))
                .willReturn(new MyTournamentFeesResponse(List.of(item)));

        mockMvc.perform(get("/api/v1/tournament-fees/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fees").isArray())
                .andExpect(jsonPath("$.data.fees[0].feeId").value(feeId.toString()))
                .andExpect(jsonPath("$.data.fees[0].title").value("2026 春季 参加費"))
                .andExpect(jsonPath("$.data.fees[0].faceAmount").value(5000))
                .andExpect(jsonPath("$.data.fees[0].alreadyPaid").value(false));
    }

    @Test
    @DisplayName("POST /{feeId}/checkout: 200 OK / clientSecret 返却")
    void checkout_200_returnsClientSecret() throws Exception {
        UUID feeId = UUID.randomUUID();
        UUID escrowId = UUID.randomUUID();
        TournamentFeeCheckoutResponse serviceResponse =
                new TournamentFeeCheckoutResponse("pi_test_secret", 42L, escrowId);

        given(tournamentFeePaymentService.checkoutFee(eq(feeId), eq(USER_ID), eq("key-001")))
                .willReturn(serviceResponse);

        TournamentFeeCheckoutRequest requestBody = new TournamentFeeCheckoutRequest("key-001");
        mockMvc.perform(post("/api/v1/tournament-fees/{feeId}/checkout", feeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value("pi_test_secret"))
                .andExpect(jsonPath("$.data.memberPaymentId").value(42))
                .andExpect(jsonPath("$.data.escrowTransactionId").value(escrowId.toString()));
    }

    @Test
    @DisplayName("POST /{feeId}/checkout: リクエストボディなし（required=false）でも 200 OK")
    void checkout_noBody_200() throws Exception {
        UUID feeId = UUID.randomUUID();
        UUID escrowId = UUID.randomUUID();
        given(tournamentFeePaymentService.checkoutFee(eq(feeId), eq(USER_ID), eq(null)))
                .willReturn(new TournamentFeeCheckoutResponse("secret", 1L, escrowId));

        mockMvc.perform(post("/api/v1/tournament-fees/{feeId}/checkout", feeId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value("secret"));
    }

    @Test
    @DisplayName("未認証: 401 を返す")
    void getMyTournamentFees_unauthenticated_401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new com.mannschaft.app.common.BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/tournament-fees/my"))
                .andExpect(status().isUnauthorized());
    }
}
