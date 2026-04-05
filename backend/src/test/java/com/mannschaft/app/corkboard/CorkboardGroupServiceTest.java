package com.mannschaft.app.corkboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CreateGroupRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardGroupEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.service.CorkboardGroupService;
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
@DisplayName("CorkboardGroupService 単体テスト")
class CorkboardGroupServiceTest {

    @Mock private CorkboardGroupRepository groupRepository;
    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardCardGroupRepository cardGroupRepository;
    @Mock private CorkboardService corkboardService;
    @Mock private CorkboardMapper corkboardMapper;
    @InjectMocks private CorkboardGroupService service;

    @Nested
    @DisplayName("createGroup")
    class CreateGroup {

        @Test
        @DisplayName("正常系: セクションが作成される")
        void 作成_正常_保存() {
            // Given
            // corkboardService はモックなので findBoardOrThrow は何もせず null を返す（戻り値は使用されない）
            given(groupRepository.save(any(CorkboardGroupEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(corkboardMapper.toGroupResponse(any(CorkboardGroupEntity.class)))
                    .willReturn(new CorkboardGroupResponse(1L, 1L, "セクション", false, 0, 0, 400, 300, (short) 0, null, null));

            CreateGroupRequest req = new CreateGroupRequest("セクション", null, null, null, null, null, null);

            // When
            CorkboardGroupResponse result = service.createGroup(1L, req);

            // Then
            assertThat(result.getName()).isEqualTo("セクション");
            verify(groupRepository).save(any(CorkboardGroupEntity.class));
        }
    }

    @Nested
    @DisplayName("addCardToGroup")
    class AddCardToGroup {

        @Test
        @DisplayName("異常系: カードが既にセクションに所属でCORKBOARD_006例外")
        void 追加_既所属_例外() {
            // Given
            given(groupRepository.findByIdAndCorkboardId(1L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardRepository.findByIdAndCorkboardId(2L, 1L)).willReturn(
                    Optional.of(com.mannschaft.app.corkboard.entity.CorkboardCardEntity.builder()
                            .corkboardId(1L).cardType("NOTE").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 1L))
                    .willReturn(Optional.of(CorkboardCardGroupEntity.builder().cardId(2L).groupId(1L).build()));

            // When / Then
            assertThatThrownBy(() -> service.addCardToGroup(1L, 1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_006"));
        }
    }

    @Nested
    @DisplayName("removeCardFromGroup")
    class RemoveCardFromGroup {

        @Test
        @DisplayName("異常系: カードがセクションに未所属でCORKBOARD_007例外")
        void 削除_未所属_例外() {
            // Given
            given(groupRepository.findByIdAndCorkboardId(1L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.removeCardFromGroup(1L, 1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_007"));
        }
    }
}
