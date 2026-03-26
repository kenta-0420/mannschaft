package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.CoinTossRequest;
import com.mannschaft.app.family.dto.CoinTossResponse;
import com.mannschaft.app.family.entity.CoinTossResultEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.service.CoinTossService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("CoinTossService 単体テスト")
class CoinTossServiceTest {

    @Mock private CoinTossResultRepository coinTossResultRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private CoinTossService service;

    @Nested
    @DisplayName("toss")
    class Toss {

        @Test
        @DisplayName("正常系: コイントス（COIN）が実行される")
        void トス_コイン_正常() {
            // Given
            given(coinTossResultRepository.countByTeamIdAndUserIdAndCreatedAtAfter(
                    eq(1L), eq(100L), any(LocalDateTime.class))).willReturn(0L);
            CoinTossResultEntity saved = CoinTossResultEntity.builder()
                    .teamId(1L).userId(100L).mode(CoinTossMode.COIN)
                    .options("[\"表\",\"裏\"]").resultIndex(0).build();
            given(coinTossResultRepository.save(any(CoinTossResultEntity.class))).willReturn(saved);

            CoinTossRequest req = new CoinTossRequest("COIN", null, null);

            // When
            ApiResponse<CoinTossResponse> result = service.toss(1L, 100L, req);

            // Then
            assertThat(result.getData().getMode()).isEqualTo("COIN");
        }

        @Test
        @DisplayName("異常系: レートリミット超過でFAMILY_007例外")
        void トス_レートリミット_例外() {
            // Given
            given(coinTossResultRepository.countByTeamIdAndUserIdAndCreatedAtAfter(
                    eq(1L), eq(100L), any(LocalDateTime.class))).willReturn(10L);

            // When / Then
            assertThatThrownBy(() -> service.toss(1L, 100L,
                    new CoinTossRequest("COIN", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_007"));
        }

        @Test
        @DisplayName("異常系: カスタム選択肢が1個でFAMILY_005例外")
        void トス_選択肢不足_例外() {
            // Given
            given(coinTossResultRepository.countByTeamIdAndUserIdAndCreatedAtAfter(
                    eq(1L), eq(100L), any(LocalDateTime.class))).willReturn(0L);

            CoinTossRequest req = new CoinTossRequest("CUSTOM", List.of("のみ"), null);

            // When / Then
            assertThatThrownBy(() -> service.toss(1L, 100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_005"));
        }
    }
}
