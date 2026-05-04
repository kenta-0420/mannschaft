package com.mannschaft.app.corkboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.dto.CorkboardResponse;
import com.mannschaft.app.corkboard.dto.CreateCorkboardRequest;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import com.mannschaft.app.corkboard.service.CorkboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardService 単体テスト")
class CorkboardServiceTest {

    @Mock private CorkboardRepository corkboardRepository;
    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardGroupRepository groupRepository;
    @Mock private CorkboardMapper corkboardMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private CorkboardService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("createPersonalBoard")
    class CreatePersonalBoard {

        @Test
        @DisplayName("正常系: 個人ボードが作成される")
        void 作成_正常_保存() {
            // Given
            given(corkboardRepository.countByOwnerId(USER_ID)).willReturn(0L);
            given(corkboardRepository.save(any(CorkboardEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(corkboardMapper.toBoardResponse(any(CorkboardEntity.class)))
                    .willReturn(new CorkboardResponse(1L, "PERSONAL", null, USER_ID,
                            "マイボード", "CORK", "ADMIN_ONLY", false, null, null, null));

            CreateCorkboardRequest req = new CreateCorkboardRequest("マイボード", null, null, null);

            // When
            CorkboardResponse result = service.createPersonalBoard(USER_ID, req);

            // Then
            assertThat(result.getName()).isEqualTo("マイボード");
            verify(corkboardRepository).save(any(CorkboardEntity.class));
        }

        @Test
        @DisplayName("異常系: ボード数上限超過でCORKBOARD_004例外")
        void 作成_上限超過_例外() {
            // Given
            given(corkboardRepository.countByOwnerId(USER_ID)).willReturn(20L);

            // When / Then
            assertThatThrownBy(() -> service.createPersonalBoard(USER_ID,
                    new CreateCorkboardRequest("ボード", null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_004"));
        }
    }

    @Nested
    @DisplayName("getPersonalBoard")
    class GetPersonalBoard {

        @Test
        @DisplayName("異常系: ボード不在でCORKBOARD_001例外")
        void 取得_不在_例外() {
            // Given
            given(corkboardRepository.findByIdAndOwnerId(1L, USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getPersonalBoard(USER_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_001"));
        }
    }

    @Nested
    @DisplayName("deletePersonalBoard")
    class DeletePersonalBoard {

        @Test
        @DisplayName("正常系: ボードが論理削除される")
        void 削除_正常() {
            // Given
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("PERSONAL").ownerId(USER_ID).name("ボード").build();
            given(corkboardRepository.findByIdAndOwnerId(1L, USER_ID)).willReturn(Optional.of(board));
            given(corkboardRepository.save(any(CorkboardEntity.class))).willReturn(board);

            // When
            service.deletePersonalBoard(USER_ID, 1L);

            // Then
            verify(corkboardRepository).save(any(CorkboardEntity.class));
        }
    }
}
