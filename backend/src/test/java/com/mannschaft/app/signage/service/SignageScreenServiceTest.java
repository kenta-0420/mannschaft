package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
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
 * {@link SignageScreenService} 単体テスト。
 *
 * <p>回帰テスト: updateScreen が findById で取得した同一インスタンスを save する
 * （toBuilder で新規行を INSERT しない）ことを ArgumentCaptor + isSameAs で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignageScreenService 単体テスト")
class SignageScreenServiceTest {

    @Mock
    private SignageScreenRepository screenRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SignageScreenService screenService;

    private static final Long SCREEN_ID = 1L;
    private static final Long CREATED_BY = 100L;

    // ======================================================
    // createScreen
    // ======================================================

    @Nested
    @DisplayName("createScreen")
    class CreateScreen {

        @Test
        @DisplayName("正常系: 10画面未満のスコープで画面が作成される")
        void createScreen_underLimit_success() {
            given(screenRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull("TEAM", 1L))
                    .willReturn(List.of());
            given(screenRepository.save(any(SignageScreenEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            SignageScreenService.CreateSignageScreenRequest req =
                    new SignageScreenService.CreateSignageScreenRequest(
                            "TEAM", 1L, "テスト画面", null,
                            SignageLayout.LANDSCAPE, 10, SignageTransitionEffect.FADE);

            SignageScreenService.SignageScreenResponse result =
                    screenService.createScreen(CREATED_BY, req);

            assertThat(result.name()).isEqualTo("テスト画面");
            verify(screenRepository).save(any(SignageScreenEntity.class));
        }

        @Test
        @DisplayName("異常系: 10画面に達したスコープで SIGNAGE_001 例外")
        void createScreen_limitReached_throws() {
            List<SignageScreenEntity> tenScreens = java.util.stream.Stream
                    .<SignageScreenEntity>generate(() -> SignageScreenEntity.builder()
                            .scopeType("TEAM").scopeId(1L).name("dummy")
                            .createdBy(1L).build())
                    .limit(10)
                    .toList();

            given(screenRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull("TEAM", 1L))
                    .willReturn(tenScreens);

            SignageScreenService.CreateSignageScreenRequest req =
                    new SignageScreenService.CreateSignageScreenRequest(
                            "TEAM", 1L, "11枚目", null,
                            null, null, null);

            assertThatThrownBy(() -> screenService.createScreen(CREATED_BY, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ======================================================
    // updateScreen — id保持回帰テスト（主目的）
    // ======================================================

    @Nested
    @DisplayName("updateScreen")
    class UpdateScreen {

        @Test
        @DisplayName("回帰: updateScreen は findById で取得した同一インスタンスを save する（toBuilder で id=null INSERT しない）")
        void updateScreen_savesSameInstance_withIdPreserved() throws Exception {
            // Given: managed entity を構築し、反射で id をセット
            SignageScreenEntity entity = SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("元の名前")
                    .layout(SignageLayout.LANDSCAPE)
                    .defaultSlideDuration(10)
                    .transitionEffect(SignageTransitionEffect.FADE)
                    .isActive(true)
                    .createdBy(CREATED_BY)
                    .build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SCREEN_ID);

            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID)).willReturn(Optional.of(entity));
            given(screenRepository.save(any(SignageScreenEntity.class))).willAnswer(inv -> inv.getArgument(0));

            SignageScreenService.UpdateSignageScreenRequest req =
                    new SignageScreenService.UpdateSignageScreenRequest(
                            "更新後の名前", null,
                            SignageLayout.PORTRAIT, null, null, null);

            // When
            SignageScreenService.SignageScreenResponse result =
                    screenService.updateScreen(SCREEN_ID, CREATED_BY, req);

            // Then: save に渡るのが findById の同一インスタンスであることを検証
            ArgumentCaptor<SignageScreenEntity> captor = ArgumentCaptor.forClass(SignageScreenEntity.class);
            verify(screenRepository).save(captor.capture());

            // isSameAs: toBuilder().build() で別インスタンスを save していたら失敗する
            assertThat(captor.getValue()).isSameAs(entity);

            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(SCREEN_ID);

            // 名前が更新されている
            assertThat(captor.getValue().getName()).isEqualTo("更新後の名前");

            // layout が更新されている
            assertThat(captor.getValue().getLayout()).isEqualTo(SignageLayout.PORTRAIT);

            // null指定フィールドは現値維持
            assertThat(captor.getValue().getTransitionEffect()).isEqualTo(SignageTransitionEffect.FADE);
            assertThat(captor.getValue().getDefaultSlideDuration()).isEqualTo(10);

            // レスポンスの name も正しい
            assertThat(result.name()).isEqualTo("更新後の名前");
        }

        @Test
        @DisplayName("正常系: null指定フィールドは現値維持（null=現値維持セマンティクス）")
        void updateScreen_nullRequest_preservesCurrentValues() throws Exception {
            SignageScreenEntity entity = SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("既存名前")
                    .layout(SignageLayout.LANDSCAPE)
                    .defaultSlideDuration(15)
                    .transitionEffect(SignageTransitionEffect.SLIDE)
                    .isActive(true)
                    .createdBy(CREATED_BY)
                    .build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SCREEN_ID);

            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID)).willReturn(Optional.of(entity));
            given(screenRepository.save(any(SignageScreenEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // 全フィールド null（何も変えない）
            SignageScreenService.UpdateSignageScreenRequest req =
                    new SignageScreenService.UpdateSignageScreenRequest(
                            null, null, null, null, null, null);

            screenService.updateScreen(SCREEN_ID, CREATED_BY, req);

            ArgumentCaptor<SignageScreenEntity> captor = ArgumentCaptor.forClass(SignageScreenEntity.class);
            verify(screenRepository).save(captor.capture());

            // 全フィールドが現値維持
            assertThat(captor.getValue().getName()).isEqualTo("既存名前");
            assertThat(captor.getValue().getLayout()).isEqualTo(SignageLayout.LANDSCAPE);
            assertThat(captor.getValue().getDefaultSlideDuration()).isEqualTo(15);
            assertThat(captor.getValue().getTransitionEffect()).isEqualTo(SignageTransitionEffect.SLIDE);
            assertThat(captor.getValue().getIsActive()).isTrue();
        }

        @Test
        @DisplayName("異常系: 画面不在なら BusinessException をスロー")
        void updateScreen_notFound_throws() {
            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID)).willReturn(Optional.empty());

            SignageScreenService.UpdateSignageScreenRequest req =
                    new SignageScreenService.UpdateSignageScreenRequest(
                            "新名前", null, null, null, null, null);

            assertThatThrownBy(() -> screenService.updateScreen(SCREEN_ID, CREATED_BY, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ======================================================
    // deleteScreen
    // ======================================================

    @Nested
    @DisplayName("deleteScreen")
    class DeleteScreen {

        @Test
        @DisplayName("正常系: 画面を論理削除する")
        void deleteScreen_softDelete() throws Exception {
            SignageScreenEntity entity = SignageScreenEntity.builder()
                    .scopeType("TEAM").scopeId(1L).name("削除対象")
                    .createdBy(CREATED_BY).build();
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, SCREEN_ID);

            given(screenRepository.findByIdAndDeletedAtIsNull(SCREEN_ID)).willReturn(Optional.of(entity));
            given(screenRepository.save(any(SignageScreenEntity.class))).willAnswer(inv -> inv.getArgument(0));

            screenService.deleteScreen(SCREEN_ID, CREATED_BY);

            verify(screenRepository).save(any(SignageScreenEntity.class));
        }
    }
}
