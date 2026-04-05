package com.mannschaft.app.equipment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.equipment.dto.AssignEquipmentRequest;
import com.mannschaft.app.equipment.dto.ConsumeEquipmentRequest;
import com.mannschaft.app.equipment.dto.ReturnEquipmentRequest;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import com.mannschaft.app.equipment.service.EquipmentAssignmentService;
import com.mannschaft.app.equipment.service.EquipmentItemService;
import com.mannschaft.app.equipment.util.QrCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentAssignmentService 単体テスト")
class EquipmentAssignmentServiceTest {

    @Mock private EquipmentItemRepository itemRepository;
    @Mock private EquipmentAssignmentRepository assignmentRepository;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private QrCodeGenerator qrCodeGenerator;
    @Mock private StorageService storageService;
    @Mock private DomainEventPublisher eventPublisher;

    private EquipmentItemService itemService;
    private EquipmentAssignmentService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        itemService = new EquipmentItemService(
                itemRepository, assignmentRepository, equipmentMapper,
                qrCodeGenerator, storageService, eventPublisher);
        service = new EquipmentAssignmentService(
                itemRepository, assignmentRepository, itemService, equipmentMapper);
    }

    private EquipmentItemEntity createItem(EquipmentStatus status, int quantity, int assigned) {
        return EquipmentItemEntity.builder()
                .teamId(TEAM_ID).name("ボール").status(status)
                .quantity(quantity).assignedQuantity(assigned)
                .isConsumable(false).build();
    }

    @Nested
    @DisplayName("assignForTeam")
    class AssignForTeam {
        @Test
        @DisplayName("異常系: メンテナンス中でEQUIPMENT_014例外")
        void 貸出_メンテナンス中_例外() {
            EquipmentItemEntity item = createItem(EquipmentStatus.MAINTENANCE, 10, 0);
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(item));
            AssignEquipmentRequest request = new AssignEquipmentRequest(USER_ID, 1, null, null);

            assertThatThrownBy(() -> service.assignForTeam(TEAM_ID, ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_014"));
        }

        @Test
        @DisplayName("異常系: 在庫不足でEQUIPMENT_003例外")
        void 貸出_在庫不足_例外() {
            EquipmentItemEntity item = createItem(EquipmentStatus.AVAILABLE, 5, 5);
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(item));
            AssignEquipmentRequest request = new AssignEquipmentRequest(USER_ID, 1, null, null);

            assertThatThrownBy(() -> service.assignForTeam(TEAM_ID, ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_003"));
        }
    }

    @Nested
    @DisplayName("returnForTeam")
    class ReturnForTeam {
        @Test
        @DisplayName("異常系: 貸出記録不在でEQUIPMENT_002例外")
        void 返却_貸出記録不在_例外() {
            EquipmentItemEntity item = createItem(EquipmentStatus.AVAILABLE, 10, 1);
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(item));
            given(assignmentRepository.findById(99L)).willReturn(Optional.empty());
            ReturnEquipmentRequest request = new ReturnEquipmentRequest(99L, null);

            assertThatThrownBy(() -> service.returnForTeam(TEAM_ID, ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_002"));
        }

        @Test
        @DisplayName("異常系: 既に返却済みでEQUIPMENT_004例外")
        void 返却_済み_例外() {
            EquipmentItemEntity item = createItem(EquipmentStatus.AVAILABLE, 10, 1);
            ReflectionTestUtils.setField(item, "id", ITEM_ID);
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(item));
            EquipmentAssignmentEntity assignment = EquipmentAssignmentEntity.builder()
                    .equipmentItemId(ITEM_ID).quantity(1).returnedAt(LocalDateTime.now()).build();
            given(assignmentRepository.findById(1L)).willReturn(Optional.of(assignment));
            ReturnEquipmentRequest request = new ReturnEquipmentRequest(1L, null);

            assertThatThrownBy(() -> service.returnForTeam(TEAM_ID, ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_004"));
        }
    }

    @Nested
    @DisplayName("consumeForTeam")
    class ConsumeForTeam {
        @Test
        @DisplayName("異常系: 消耗品でない備品の消費でEQUIPMENT_005例外")
        void 消費_消耗品でない_例外() {
            EquipmentItemEntity item = createItem(EquipmentStatus.AVAILABLE, 10, 0);
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(item));
            ConsumeEquipmentRequest request = new ConsumeEquipmentRequest(1, USER_ID, null);

            assertThatThrownBy(() -> service.consumeForTeam(TEAM_ID, ITEM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_005"));
        }
    }
}
