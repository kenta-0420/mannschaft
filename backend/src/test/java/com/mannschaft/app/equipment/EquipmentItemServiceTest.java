package com.mannschaft.app.equipment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.equipment.dto.CreateEquipmentItemRequest;
import com.mannschaft.app.equipment.dto.EquipmentItemResponse;
import com.mannschaft.app.equipment.dto.PresignedUrlRequest;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import com.mannschaft.app.equipment.util.QrCodeGenerator;
import com.mannschaft.app.equipment.service.EquipmentItemService;
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
@DisplayName("EquipmentItemService 単体テスト")
class EquipmentItemServiceTest {

    @Mock private EquipmentItemRepository itemRepository;
    @Mock private EquipmentAssignmentRepository assignmentRepository;
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private QrCodeGenerator qrCodeGenerator;
    @Mock private StorageService storageService;
    @Mock private DomainEventPublisher eventPublisher;

    @InjectMocks
    private EquipmentItemService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ITEM_ID = 10L;

    @Nested
    @DisplayName("createForTeam")
    class CreateForTeam {
        @Test
        @DisplayName("正常系: チーム備品が作成される")
        void 作成_正常_保存() {
            given(qrCodeGenerator.generate(EquipmentScopeType.TEAM, TEAM_ID)).willReturn("QR-001");
            CreateEquipmentItemRequest request = new CreateEquipmentItemRequest(
                    "ボール", null, "スポーツ用品", null, null, null, null, null);
            EquipmentItemEntity saved = EquipmentItemEntity.builder()
                    .teamId(TEAM_ID).name("ボール").qrCode("QR-001").build();
            given(itemRepository.save(any())).willReturn(saved);
            given(equipmentMapper.toItemResponse(saved)).willReturn(new EquipmentItemResponse(
                    null, TEAM_ID, null, "ボール", null, null, null, null, null, null, null, null, null, null, null, "QR-001", null, null));

            EquipmentItemResponse result = service.createForTeam(TEAM_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("getByTeam")
    class GetByTeam {
        @Test
        @DisplayName("異常系: 備品不在でEQUIPMENT_001例外")
        void 取得_不在_例外() {
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getByTeam(TEAM_ID, ITEM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_001"));
        }
    }

    @Nested
    @DisplayName("deleteForTeam")
    class DeleteForTeam {
        @Test
        @DisplayName("異常系: 貸出中の備品削除でEQUIPMENT_008例外")
        void 削除_貸出中_例外() {
            EquipmentItemEntity entity = EquipmentItemEntity.builder()
                    .teamId(TEAM_ID).name("ボール").build();
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(assignmentRepository.existsByEquipmentItemIdAndReturnedAtIsNull(any())).willReturn(true);

            assertThatThrownBy(() -> service.deleteForTeam(TEAM_ID, ITEM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_008"));
        }
    }

    @Nested
    @DisplayName("deleteImageForTeam")
    class DeleteImageForTeam {
        @Test
        @DisplayName("正常系: 画像が削除されS3イベントが発行される")
        void 画像削除_正常_イベント発行() {
            EquipmentItemEntity entity = EquipmentItemEntity.builder()
                    .teamId(TEAM_ID).name("ボール").s3Key("old-key").build();
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(itemRepository.save(entity)).willReturn(entity);

            service.deleteImageForTeam(TEAM_ID, ITEM_ID);

            verify(eventPublisher).publish(any(com.mannschaft.app.common.event.DomainEvent.class));
        }
    }

    @Nested
    @DisplayName("getPresignedUrlForTeam")
    class GetPresignedUrlForTeam {
        @Test
        @DisplayName("異常系: 不正なコンテンツタイプでEQUIPMENT_010例外")
        void プレサイン_不正タイプ_例外() {
            EquipmentItemEntity entity = EquipmentItemEntity.builder()
                    .teamId(TEAM_ID).name("ボール").build();
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));
            PresignedUrlRequest request = new PresignedUrlRequest("text/plain", 1000L);

            assertThatThrownBy(() -> service.getPresignedUrlForTeam(TEAM_ID, ITEM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_010"));
        }

        @Test
        @DisplayName("異常系: ファイルサイズ超過でEQUIPMENT_011例外")
        void プレサイン_サイズ超過_例外() {
            EquipmentItemEntity entity = EquipmentItemEntity.builder()
                    .teamId(TEAM_ID).name("ボール").build();
            given(itemRepository.findByIdAndTeamId(ITEM_ID, TEAM_ID)).willReturn(Optional.of(entity));
            PresignedUrlRequest request = new PresignedUrlRequest("image/jpeg", 6 * 1024 * 1024L);

            assertThatThrownBy(() -> service.getPresignedUrlForTeam(TEAM_ID, ITEM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("EQUIPMENT_011"));
        }
    }
}
