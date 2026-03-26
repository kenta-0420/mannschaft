package com.mannschaft.app.digest;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.digest.dto.DigestConfigRequest;
import com.mannschaft.app.digest.dto.DigestConfigResponse;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.repository.TimelineDigestConfigRepository;
import com.mannschaft.app.digest.service.DigestConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DigestConfigService 単体テスト")
class DigestConfigServiceTest {

    @Mock private TimelineDigestConfigRepository configRepository;
    @Mock private DigestMapper digestMapper;
    @Mock private DigestProperties digestProperties;

    @InjectMocks
    private DigestConfigService service;

    @Nested
    @DisplayName("getConfig")
    class GetConfig {
        @Test
        @DisplayName("異常系: 設定不在でDIGEST_014例外")
        void 取得_不在_例外() {
            given(configRepository.findByScopeTypeAndScopeId(DigestScopeType.TEAM, 1L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConfig("TEAM", 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_014"));
        }

        @Test
        @DisplayName("正常系: 設定が返却される")
        void 取得_正常_返却() {
            TimelineDigestConfigEntity entity = TimelineDigestConfigEntity.builder()
                    .scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(configRepository.findByScopeTypeAndScopeId(DigestScopeType.TEAM, 1L))
                    .willReturn(Optional.of(entity));
            given(digestMapper.toConfigResponse(entity)).willReturn(
                    new DigestConfigResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

            DigestConfigResponse result = service.getConfig("TEAM", 1L);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("deleteConfig")
    class DeleteConfig {
        @Test
        @DisplayName("異常系: 設定不在でDIGEST_014例外")
        void 削除_不在_例外() {
            given(configRepository.findByScopeTypeAndScopeId(DigestScopeType.TEAM, 1L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteConfig("TEAM", 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_014"));
        }

        @Test
        @DisplayName("正常系: 設定が無効化される")
        void 削除_正常_無効化() {
            TimelineDigestConfigEntity entity = TimelineDigestConfigEntity.builder()
                    .scopeType(DigestScopeType.TEAM).scopeId(1L).isEnabled(true).build();
            given(configRepository.findByScopeTypeAndScopeId(DigestScopeType.TEAM, 1L))
                    .willReturn(Optional.of(entity));
            given(configRepository.save(any())).willReturn(entity);

            service.deleteConfig("TEAM", 1L);
            verify(configRepository).save(any());
        }
    }

    @Nested
    @DisplayName("createOrUpdateConfig")
    class CreateOrUpdateConfig {
        @Test
        @DisplayName("異常系: 無効なタイムゾーンでDIGEST_022例外")
        void 作成_無効タイムゾーン_例外() {
            DigestConfigRequest request = new DigestConfigRequest(
                    "TEAM", 1L, "MANUAL", null, null, "SUMMARY", null, null, null, null, null, null, null, "Invalid/Zone", null, null, null, null);

            assertThatThrownBy(() -> service.createOrUpdateConfig(request, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_022"));
        }

        @Test
        @DisplayName("異常系: WEEKLY指定で曜日範囲外でDIGEST_016例外")
        void 作成_曜日範囲外_例外() {
            DigestConfigRequest request = new DigestConfigRequest(
                    "TEAM", 1L, "WEEKLY", LocalTime.of(9, 0), 7, "SUMMARY", null, null, null, null, null, null, null, "Asia/Tokyo", null, null, null, null);

            assertThatThrownBy(() -> service.createOrUpdateConfig(request, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_016"));
        }

        @Test
        @DisplayName("異常系: DAILY指定で時刻なしでDIGEST_023例外")
        void 作成_時刻なし_例外() {
            DigestConfigRequest request = new DigestConfigRequest(
                    "TEAM", 1L, "DAILY", null, null, "SUMMARY", null, null, null, null, null, null, null, "Asia/Tokyo", null, null, null, null);

            assertThatThrownBy(() -> service.createOrUpdateConfig(request, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_023"));
        }
    }
}
