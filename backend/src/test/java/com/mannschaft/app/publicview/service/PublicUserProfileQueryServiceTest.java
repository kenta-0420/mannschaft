package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.publicview.dto.PublicUserProfileResponse;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link PublicUserProfileQueryService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 2: 公開プロフィール経路で {@code avatarUrl} が DB の生 R2 キーではなく
 * {@link MediaUrlResolver} の解決済み署名付き表示 URL になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicUserProfileQueryService 単体テスト")
class PublicUserProfileQueryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BlogPostRepository blogPostRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private PublicUserProfileQueryService service;

    @Test
    @DisplayName("公開プロフィール経路: avatarUrl が署名付き表示 URL へ解決される")
    void getPublicProfile_avatarUrlが解決される() {
        String avatarKey = "user/88/avatar/me.png";
        String signedAvatar = "https://cdn.example.com/signed/avatar.png";

        UserEntity user = UserEntity.builder()
                .email("public@example.com")
                .passwordHash("hash")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .avatarUrl(avatarKey)
                .publicProfileEnabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(user, "id", 88L);

        given(userRepository.findById(88L)).willReturn(Optional.of(user));
        given(mediaUrlResolver.resolve(avatarKey)).willReturn(signedAvatar);

        PublicUserProfileResponse result = service.getPublicProfile(88L);

        assertThat(result.userId()).isEqualTo(88L);
        assertThat(result.avatarUrl()).isEqualTo(signedAvatar);
    }
}
