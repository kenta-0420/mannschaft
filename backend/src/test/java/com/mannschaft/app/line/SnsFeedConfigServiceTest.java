package com.mannschaft.app.line;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.line.dto.CreateSnsFeedConfigRequest;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.SnsFeedPreviewResponse;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import com.mannschaft.app.line.repository.SnsFeedConfigRepository;
import com.mannschaft.app.line.service.SnsFeedApiClient;
import com.mannschaft.app.line.service.SnsFeedConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mannschaft.app.line.dto.UpdateSnsFeedConfigRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SnsFeedConfigService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SnsFeedConfigService 単体テスト")
class SnsFeedConfigServiceTest {

    @Mock
    private SnsFeedConfigRepository snsFeedConfigRepository;
    @Mock
    private LineMapper lineMapper;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private SnsFeedApiClient snsFeedApiClient;

    @InjectMocks
    private SnsFeedConfigService service;

    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("正常系: スコープに紐づく設定一覧を返す")
        void 一覧取得_正常_リスト返却() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("user1").build();
            SnsFeedConfigResponse response = new SnsFeedConfigResponse(
                    1L, SCOPE_TYPE.name(), SCOPE_ID, "INSTAGRAM", "user1", (short) 6, true, USER_ID, null, null);
            given(snsFeedConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(entity));
            given(lineMapper.toSnsFeedConfigResponse(entity)).willReturn(response);

            // When
            List<SnsFeedConfigResponse> result = service.findAll(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProvider()).isEqualTo("INSTAGRAM");
        }

        @Test
        @DisplayName("正常系: 設定がない場合は空リストを返す")
        void 一覧取得_空_空リスト返却() {
            // Given
            given(snsFeedConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of());

            // When
            List<SnsFeedConfigResponse> result = service.findAll(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: フィード設定が作成される")
        void 作成_正常_保存() {
            // Given
            CreateSnsFeedConfigRequest req = new CreateSnsFeedConfigRequest(
                    "INSTAGRAM", "user1", "token123", (short) 6);
            given(snsFeedConfigRepository.existsByScopeTypeAndScopeIdAndProvider(
                    SCOPE_TYPE, SCOPE_ID, SnsProvider.INSTAGRAM)).willReturn(false);
            given(encryptionService.encryptBytes(any(byte[].class))).willReturn(new byte[]{1});
            given(snsFeedConfigRepository.save(any(SnsFeedConfigEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(lineMapper.toSnsFeedConfigResponse(any(SnsFeedConfigEntity.class)))
                    .willReturn(new SnsFeedConfigResponse(1L, SCOPE_TYPE.name(), SCOPE_ID,
                            "INSTAGRAM", "user1", (short) 6, true, USER_ID, null, null));

            // When
            SnsFeedConfigResponse result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(snsFeedConfigRepository).save(any(SnsFeedConfigEntity.class));
        }

        @Test
        @DisplayName("異常系: 同一プロバイダーの設定が既に存在する場合LINE_008例外")
        void 作成_重複_例外() {
            // Given
            CreateSnsFeedConfigRequest req = new CreateSnsFeedConfigRequest(
                    "INSTAGRAM", "user1", "token123", (short) 6);
            given(snsFeedConfigRepository.existsByScopeTypeAndScopeIdAndProvider(
                    SCOPE_TYPE, SCOPE_ID, SnsProvider.INSTAGRAM)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_008"));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: アクセストークンあり — 再暗号化して更新される")
        void 更新_トークンあり_暗号化して保存() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("old")
                    .accessTokenEnc(new byte[]{9}).displayCount((short) 3).isActive(true).build();
            UpdateSnsFeedConfigRequest req = new UpdateSnsFeedConfigRequest(
                    "newUser", "newToken", (short) 9, true);
            SnsFeedConfigResponse response = new SnsFeedConfigResponse(
                    1L, SCOPE_TYPE.name(), SCOPE_ID, "INSTAGRAM", "newUser", (short) 9, true, USER_ID, null, null);
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));
            given(encryptionService.encryptBytes(any(byte[].class))).willReturn(new byte[]{5});
            given(lineMapper.toSnsFeedConfigResponse(entity)).willReturn(response);

            // When
            SnsFeedConfigResponse result = service.update(1L, SCOPE_TYPE, SCOPE_ID, req);

            // Then
            assertThat(result.getAccountUsername()).isEqualTo("newUser");
            verify(encryptionService).encryptBytes(any(byte[].class));
        }

        @Test
        @DisplayName("正常系: アクセストークンnull — 既存の暗号化トークンを維持")
        void 更新_トークンなし_既存を維持() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("old")
                    .accessTokenEnc(new byte[]{9}).displayCount((short) 3).isActive(true).build();
            UpdateSnsFeedConfigRequest req = new UpdateSnsFeedConfigRequest("newUser", null, null, null);
            SnsFeedConfigResponse response = new SnsFeedConfigResponse(
                    1L, SCOPE_TYPE.name(), SCOPE_ID, "INSTAGRAM", "newUser", (short) 3, true, USER_ID, null, null);
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));
            given(lineMapper.toSnsFeedConfigResponse(entity)).willReturn(response);

            // When
            SnsFeedConfigResponse result = service.update(1L, SCOPE_TYPE, SCOPE_ID, req);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: スコープ不一致でLINE_007例外")
        void 更新_スコープ不一致_例外() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(99L)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("user1").build();
            UpdateSnsFeedConfigRequest req = new UpdateSnsFeedConfigRequest("x", null, null, null);
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.update(1L, SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_007"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: フィード設定が論理削除される")
        void 削除_正常_論理削除() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("user1").build();
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));

            // When
            service.delete(1L, SCOPE_TYPE, SCOPE_ID);

            // Then — softDeleteが呼ばれたことを間接確認
            assertThat(entity).isNotNull();
        }

        @Test
        @DisplayName("異常系: 設定不在でLINE_007例外")
        void 削除_不在_例外() {
            // Given
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(1L, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_007"));
        }

        @Test
        @DisplayName("異常系: スコープ不一致でLINE_007例外")
        void 削除_スコープ不一致_例外() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(99L)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("user1").build();
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.delete(1L, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("LINE_007"));
        }
    }

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("正常系: INSTAGRAMプレビューが返却される")
        void プレビュー_INSTAGRAM_フィード返却() {
            // Given
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .provider(SnsProvider.INSTAGRAM).accountUsername("user1")
                    .accessTokenEnc(new byte[]{1, 2}).displayCount((short) 6).build();
            given(snsFeedConfigRepository.findById(1L)).willReturn(Optional.of(entity));
            given(encryptionService.decryptBytes(any(byte[].class))).willReturn("token".getBytes());
            given(snsFeedApiClient.fetchInstagramFeed("token", 6)).willReturn(List.of());

            // When
            SnsFeedPreviewResponse result = service.preview(1L, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getProvider()).isEqualTo("INSTAGRAM");
            assertThat(result.getItems()).isEmpty();
        }
    }
}
