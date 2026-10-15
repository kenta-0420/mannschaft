package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.UpdateBlogSettingsRequest;
import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import com.mannschaft.app.cms.repository.UserBlogSettingsRepository;
import com.mannschaft.app.cms.service.UserBlogSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserBlogSettingsService 単体テスト")
class UserBlogSettingsServiceTest {

    @Mock
    private UserBlogSettingsRepository settingsRepository;
    @Mock
    private CmsMapper cmsMapper;

    @InjectMocks
    private UserBlogSettingsService service;

    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("getOrCreateSettings")
    class GetOrCreateSettings {
        @Test
        @DisplayName("正常系: 既存設定が返却される")
        void 取得_既存_返却() {
            UserBlogSettingsEntity entity = UserBlogSettingsEntity.builder().userId(USER_ID).build();
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
            given(cmsMapper.toBlogSettingsResponse(entity)).willReturn(new BlogSettingsResponse(null, null, null));

            BlogSettingsResponse result = service.getOrCreateSettings(USER_ID);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: 設定不在時にデフォルト設定が作成される")
        void 取得_不在_デフォルト作成() {
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            UserBlogSettingsEntity newEntity = UserBlogSettingsEntity.builder().userId(USER_ID).build();
            given(settingsRepository.save(any())).willReturn(newEntity);
            given(cmsMapper.toBlogSettingsResponse(newEntity)).willReturn(new BlogSettingsResponse(null, null, null));

            service.getOrCreateSettings(USER_ID);
            verify(settingsRepository).save(any(UserBlogSettingsEntity.class));
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {
        @Test
        @DisplayName("正常系: 設定が更新される")
        void 更新_正常_保存() {
            UserBlogSettingsEntity entity = UserBlogSettingsEntity.builder().userId(USER_ID).build();
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.of(entity));
            given(settingsRepository.save(entity)).willReturn(entity);
            given(cmsMapper.toBlogSettingsResponse(entity)).willReturn(new BlogSettingsResponse(null, null, null));

            UpdateBlogSettingsRequest request = new UpdateBlogSettingsRequest(true, null, null);
            BlogSettingsResponse result = service.updateSettings(USER_ID, request);

            assertThat(result).isNotNull();
            verify(settingsRepository).save(entity);
        }
    }
}
