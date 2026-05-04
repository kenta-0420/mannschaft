package com.mannschaft.app.corkboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.dto.CorkboardGroupResponse;
import com.mannschaft.app.corkboard.dto.CreateGroupRequest;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardCardGroupEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardGroupEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.service.CorkboardGroupService;
import com.mannschaft.app.corkboard.service.CorkboardPermissionService;
import com.mannschaft.app.corkboard.service.CorkboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardGroupService 単体テスト")
class CorkboardGroupServiceTest {

    @Mock private CorkboardGroupRepository groupRepository;
    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardCardGroupRepository cardGroupRepository;
    @Mock private CorkboardService corkboardService;
    @Mock private CorkboardMapper corkboardMapper;
    @Mock private CorkboardPermissionService permissionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private CorkboardGroupService service;

    private CorkboardEntity personalBoard() {
        return CorkboardEntity.builder()
                .scopeType("PERSONAL")
                .ownerId(1L)
                .name("テストボード")
                .build();
    }

    @Nested
    @DisplayName("createGroup")
    class CreateGroup {

        @Test
        @DisplayName("正常系: セクションが作成される")
        void 作成_正常_保存() {
            // Given
            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.save(any(CorkboardGroupEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(corkboardMapper.toGroupResponse(any(CorkboardGroupEntity.class)))
                    .willReturn(new CorkboardGroupResponse(1L, 1L, "セクション", false, 0, 0, 400, 300, (short) 0, null, null));

            CreateGroupRequest req = new CreateGroupRequest("セクション", null, null, null, null, null, null);

            // When
            CorkboardGroupResponse result = service.createGroup(1L, 1L, req);

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
            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.findByIdAndCorkboardId(1L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardRepository.findByIdAndCorkboardId(2L, 1L)).willReturn(
                    Optional.of(com.mannschaft.app.corkboard.entity.CorkboardCardEntity.builder()
                            .corkboardId(1L).cardType("NOTE").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 1L))
                    .willReturn(Optional.of(CorkboardCardGroupEntity.builder().cardId(2L).groupId(1L).build()));

            // When / Then
            assertThatThrownBy(() -> service.addCardToGroup(1L, 1L, 1L, 2L))
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
            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.findByIdAndCorkboardId(1L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.removeCardFromGroup(1L, 1L, 1L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_007"));
        }
    }

    /**
     * F09.8 積み残し件1 (V9.097): primary section (corkboard_cards.section_id) の更新を検証する。
     */
    @Nested
    @DisplayName("addCardToGroup / removeCardFromGroup の sectionId 更新（積み残し件1）")
    class SectionIdUpdate {

        @Test
        @DisplayName("addCardToGroup 正常系: card.sectionId が groupId に更新されて save される")
        void 追加_sectionId_反映() {
            // Given
            CorkboardCardEntity card = CorkboardCardEntity.builder()
                    .corkboardId(1L)
                    .cardType("MEMO")
                    .createdBy(1L)
                    .build();

            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.findByIdAndCorkboardId(10L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardRepository.findByIdAndCorkboardId(2L, 1L)).willReturn(Optional.of(card));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 10L)).willReturn(Optional.empty());

            // When
            service.addCardToGroup(1L, 1L, 10L, 2L);

            // Then: 中間テーブル INSERT + cardRepository.save(card) が呼ばれ sectionId=10 となること
            verify(cardGroupRepository).save(any(CorkboardCardGroupEntity.class));
            ArgumentCaptor<CorkboardCardEntity> captor = ArgumentCaptor.forClass(CorkboardCardEntity.class);
            verify(cardRepository).save(captor.capture());
            assertThat(captor.getValue().getSectionId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("removeCardFromGroup 正常系: 一致時は sectionId が null に戻る")
        void 削除_一致_sectionId_クリア() {
            // Given
            CorkboardCardEntity card = CorkboardCardEntity.builder()
                    .corkboardId(1L)
                    .cardType("MEMO")
                    .createdBy(1L)
                    .sectionId(10L)
                    .build();

            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.findByIdAndCorkboardId(10L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 10L)).willReturn(
                    Optional.of(CorkboardCardGroupEntity.builder().cardId(2L).groupId(10L).build()));
            given(cardRepository.findByIdAndCorkboardId(2L, 1L)).willReturn(Optional.of(card));

            // When
            service.removeCardFromGroup(1L, 1L, 10L, 2L);

            // Then
            verify(cardGroupRepository).delete(any(CorkboardCardGroupEntity.class));
            ArgumentCaptor<CorkboardCardEntity> captor = ArgumentCaptor.forClass(CorkboardCardEntity.class);
            verify(cardRepository).save(captor.capture());
            assertThat(captor.getValue().getSectionId()).isNull();
        }

        @Test
        @DisplayName("removeCardFromGroup: 現在の sectionId が異なる group の場合は cards.save を行わない")
        void 削除_不一致_sectionId_保持() {
            // Given: card は別セクション (99) に属しており、削除対象は groupId=10
            CorkboardCardEntity card = CorkboardCardEntity.builder()
                    .corkboardId(1L)
                    .cardType("MEMO")
                    .createdBy(1L)
                    .sectionId(99L)
                    .build();

            given(corkboardService.findBoardOrThrow(1L)).willReturn(personalBoard());
            given(groupRepository.findByIdAndCorkboardId(10L, 1L)).willReturn(
                    Optional.of(CorkboardGroupEntity.builder().corkboardId(1L).name("セクション").build()));
            given(cardGroupRepository.findByCardIdAndGroupId(2L, 10L)).willReturn(
                    Optional.of(CorkboardCardGroupEntity.builder().cardId(2L).groupId(10L).build()));
            given(cardRepository.findByIdAndCorkboardId(2L, 1L)).willReturn(Optional.of(card));

            // When
            service.removeCardFromGroup(1L, 1L, 10L, 2L);

            // Then: 中間テーブル削除はする / cards.save は呼ばれない
            verify(cardGroupRepository).delete(any(CorkboardCardGroupEntity.class));
            verify(cardRepository, never()).save(any(CorkboardCardEntity.class));
            assertThat(card.getSectionId()).isEqualTo(99L);
        }
    }
}
