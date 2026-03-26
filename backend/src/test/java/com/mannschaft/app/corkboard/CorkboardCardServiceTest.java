package com.mannschaft.app.corkboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.dto.CreateCardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.service.CorkboardCardService;
import com.mannschaft.app.corkboard.service.CorkboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardCardService 単体テスト")
class CorkboardCardServiceTest {

    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardService corkboardService;
    @Mock private CorkboardMapper corkboardMapper;
    @InjectMocks private CorkboardCardService service;

    @Nested
    @DisplayName("createCard")
    class CreateCard {

        @Test
        @DisplayName("正常系: カードが追加される")
        void 追加_正常_保存() {
            // Given
            // corkboardService はモックなので findBoardOrThrow は何もせず null を返す（戻り値は使用されない）
            given(cardRepository.countByCorkboardId(1L)).willReturn(0L);
            given(cardRepository.save(any(CorkboardCardEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(corkboardMapper.toCardResponse(any(CorkboardCardEntity.class)))
                    .willReturn(new CorkboardCardResponse(1L, 1L, "NOTE", null, null,
                            null, "タイトル", "本文", null, null, null, null, "NONE", "MEDIUM", 0, 0, 0, null, null, false, 1L, null, null));

            CreateCardRequest req = new CreateCardRequest("NOTE", null, null,
                    "タイトル", "本文", null, null, null, null, null, null, null, null);

            // When
            CorkboardCardResponse result = service.createCard(1L, 1L, req);

            // Then
            assertThat(result.getTitle()).isEqualTo("タイトル");
            verify(cardRepository).save(any(CorkboardCardEntity.class));
        }

        @Test
        @DisplayName("異常系: カード数上限超過でCORKBOARD_005例外")
        void 追加_上限超過_例外() {
            // Given
            // corkboardService はモックなので findBoardOrThrow は何もせず null を返す（戻り値は使用されない）
            given(cardRepository.countByCorkboardId(1L)).willReturn(200L);

            // When / Then
            assertThatThrownBy(() -> service.createCard(1L, 1L,
                    new CreateCardRequest("NOTE", null, null, "タイトル", null, null,
                            null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_005"));
        }
    }

    @Nested
    @DisplayName("deleteCard")
    class DeleteCard {

        @Test
        @DisplayName("異常系: カード不在でCORKBOARD_002例外")
        void 削除_不在_例外() {
            // Given
            given(cardRepository.findByIdAndCorkboardId(1L, 1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteCard(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_002"));
        }
    }
}
