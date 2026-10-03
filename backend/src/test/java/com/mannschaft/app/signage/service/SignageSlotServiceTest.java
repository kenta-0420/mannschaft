package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageSlotType;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.entity.SignageSlotEntity;
import com.mannschaft.app.signage.repository.SignageSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SignageSlotService} 単体テスト。
 *
 * <p>回帰テスト: updateSlot が findById で取得した同一インスタンスを save する
 * （toBuilder で新規行を INSERT しない）ことを ArgumentCaptor + isSameAs で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageSlotService 単体テスト")
class SignageSlotServiceTest {

    @Mock
    private SignageSlotRepository slotRepository;

    @Mock
    private SignageScreenService screenService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SignageSlotService slotService;

    private static final Long SLOT_ID = 10L;
    private static final Long SCREEN_ID = 1L;
    private static final Long ACTOR = 100L;

    /** 認可解決用: SCREEN_ID に対応する TEAM スコープのダミー画面を返すスタブを仕込む。 */
    private void stubScreenScope() {
        SignageScreenEntity screen = SignageScreenEntity.builder()
                .scopeType("TEAM").scopeId(5L).name("dummy").createdBy(1L).build();
        given(screenService.findScreenOrThrow(SCREEN_ID)).willReturn(screen);
    }

    // ======================================================
    // addSlot
    // ======================================================

    @Nested
    @DisplayName("addSlot")
    class AddSlot {

        @Test
        @DisplayName("正常系: スロットが追加され slotOrder が最大値+1 になる")
        void addSlot_success_withNextOrder() {
            stubScreenScope();
            given(slotRepository.findMaxSlotOrderByScreenId(SCREEN_ID)).willReturn(Optional.of(3));
            given(slotRepository.save(any(SignageSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));

            SignageSlotService.AddSignageSlotRequest req =
                    new SignageSlotService.AddSignageSlotRequest(
                            SignageSlotType.ANNOUNCEMENT, "post-001", 15, null);

            SignageSlotService.SignageSlotResponse result = slotService.addSlot(SCREEN_ID, ACTOR, req);

            assertThat(result.slotOrder()).isEqualTo(4);
            verify(slotRepository).save(any(SignageSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: スロットが0件の場合 slotOrder=1 になる")
        void addSlot_firstSlot_orderIsOne() {
            stubScreenScope();
            given(slotRepository.findMaxSlotOrderByScreenId(SCREEN_ID)).willReturn(Optional.empty());
            given(slotRepository.save(any(SignageSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));

            SignageSlotService.AddSignageSlotRequest req =
                    new SignageSlotService.AddSignageSlotRequest(
                            SignageSlotType.ANNOUNCEMENT, "post-001", 10, null);

            SignageSlotService.SignageSlotResponse result = slotService.addSlot(SCREEN_ID, ACTOR, req);

            assertThat(result.slotOrder()).isEqualTo(1);
        }
    }

    // ======================================================
    // updateSlot — id保持回帰テスト（主目的）
    // ======================================================

    @Nested
    @DisplayName("updateSlot")
    class UpdateSlot {

        @Test
        @DisplayName("回帰: updateSlot は findById で取得した同一インスタンスを save する（toBuilder で id=null INSERT しない）")
        void updateSlot_savesSameInstance_withIdPreserved() throws Exception {
            // Given: managed entity を構築し、反射で id をセット
            SignageSlotEntity entity = SignageSlotEntity.builder()
                    .screenId(SCREEN_ID)
                    .slotType(SignageSlotType.ANNOUNCEMENT)
                    .slotOrder(1)
                    .slideDuration(10)
                    .contentConfig("{\"key\":\"value\"}")
                    .isActive(true)
                    .build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SLOT_ID);

            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(SignageSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            stubScreenScope();

            SignageSlotService.UpdateSignageSlotRequest req =
                    new SignageSlotService.UpdateSignageSlotRequest(30, "{\"newKey\":\"newVal\"}", false);

            // When
            SignageSlotService.SignageSlotResponse result = slotService.updateSlot(SLOT_ID, ACTOR, req);

            // Then: save に渡るのが findById の同一インスタンスであることを検証
            ArgumentCaptor<SignageSlotEntity> captor = ArgumentCaptor.forClass(SignageSlotEntity.class);
            verify(slotRepository).save(captor.capture());

            // isSameAs: toBuilder().build() で別インスタンスを save していたら失敗する
            assertThat(captor.getValue()).isSameAs(entity);

            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(SLOT_ID);

            // slideDuration が更新されている
            assertThat(captor.getValue().getSlideDuration()).isEqualTo(30);

            // contentConfig が更新されている
            assertThat(captor.getValue().getContentConfig()).isEqualTo("{\"newKey\":\"newVal\"}");

            // isActive が更新されている
            assertThat(captor.getValue().getIsActive()).isFalse();

            // レスポンスの値も正しい
            assertThat(result.durationSeconds()).isEqualTo(30);
        }

        @Test
        @DisplayName("正常系: null指定フィールドは現値維持（null=現値維持セマンティクス）")
        void updateSlot_nullRequest_preservesCurrentValues() throws Exception {
            SignageSlotEntity entity = SignageSlotEntity.builder()
                    .screenId(SCREEN_ID)
                    .slotType(SignageSlotType.ANNOUNCEMENT)
                    .slotOrder(2)
                    .slideDuration(20)
                    .contentConfig("{\"existing\":true}")
                    .isActive(true)
                    .build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SLOT_ID);

            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(SignageSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            stubScreenScope();

            // 全フィールド null（何も変えない）
            SignageSlotService.UpdateSignageSlotRequest req =
                    new SignageSlotService.UpdateSignageSlotRequest(null, null, null);

            slotService.updateSlot(SLOT_ID, ACTOR, req);

            ArgumentCaptor<SignageSlotEntity> captor = ArgumentCaptor.forClass(SignageSlotEntity.class);
            verify(slotRepository).save(captor.capture());

            // 全フィールドが現値維持
            assertThat(captor.getValue().getSlideDuration()).isEqualTo(20);
            assertThat(captor.getValue().getContentConfig()).isEqualTo("{\"existing\":true}");
            assertThat(captor.getValue().getIsActive()).isTrue();
        }

        @Test
        @DisplayName("異常系: スロット不在なら BusinessException をスロー")
        void updateSlot_notFound_throws() {
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            SignageSlotService.UpdateSignageSlotRequest req =
                    new SignageSlotService.UpdateSignageSlotRequest(30, null, null);

            assertThatThrownBy(() -> slotService.updateSlot(SLOT_ID, ACTOR, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ======================================================
    // listSlots
    // ======================================================

    @Nested
    @DisplayName("listSlots")
    class ListSlots {

        @Test
        @DisplayName("正常系: 画面に紐づくスロット一覧を返す")
        void listSlots_returnsAll() {
            SignageSlotEntity slot1 = SignageSlotEntity.builder()
                    .screenId(SCREEN_ID).slotType(SignageSlotType.ANNOUNCEMENT).slotOrder(1).build();
            SignageSlotEntity slot2 = SignageSlotEntity.builder()
                    .screenId(SCREEN_ID).slotType(SignageSlotType.ANNOUNCEMENT).slotOrder(2).build();

            given(slotRepository.findByScreenIdOrderBySlotOrderAsc(SCREEN_ID))
                    .willReturn(List.of(slot1, slot2));

            List<SignageSlotService.SignageSlotResponse> result = slotService.listSlots(SCREEN_ID);

            assertThat(result).hasSize(2);
        }
    }

    // ======================================================
    // removeSlot
    // ======================================================

    @Nested
    @DisplayName("removeSlot")
    class RemoveSlot {

        @Test
        @DisplayName("正常系: スロットを物理削除する")
        void removeSlot_success() throws Exception {
            SignageSlotEntity entity = SignageSlotEntity.builder()
                    .screenId(SCREEN_ID).slotType(SignageSlotType.ANNOUNCEMENT).slotOrder(1).build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SLOT_ID);

            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            stubScreenScope();

            slotService.removeSlot(SLOT_ID, ACTOR);

            verify(slotRepository).deleteById(SLOT_ID);
        }

        @Test
        @DisplayName("異常系: スロット不在なら BusinessException をスロー")
        void removeSlot_notFound_throws() {
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> slotService.removeSlot(SLOT_ID, ACTOR))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
