package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link BlogAuthorFavoriteResolver} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>著者アバターが {@link MediaUrlResolver} 解決後の署名付き表示 URL として返ることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogAuthorFavoriteResolver 単体テスト")
class BlogAuthorFavoriteResolverTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private BlogAuthorFavoriteResolver resolver;

    @Test
    @DisplayName("resolveAll: avatarUrl は生 R2 キーでなく署名付き表示 URL を返す")
    void resolveAll_resolvesAvatarUrl() {
        UserEntity user = UserEntity.builder()
                .email("author@example.com")
                .displayName("著者太郎")
                .avatarUrl("user/5/avatar/raw.png")
                .build();
        ReflectionTestUtils.setField(user, "id", 5L);

        given(userRepository.findByIdIn(any())).willReturn(List.of(user));
        given(mediaUrlResolver.resolve("user/5/avatar/raw.png"))
                .willReturn("https://cdn.example/signed/user-5");

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("5"), 5L);

        FavoriteEntityMetaDto meta = result.get("5");
        assertThat(meta).isNotNull();
        assertThat(meta.entityType()).isEqualTo(FavoriteEntityType.BLOG_AUTHOR);
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/user-5");
        assertThat(meta.iconUrl()).isNotEqualTo("user/5/avatar/raw.png");
        // 自分のプロフィールは編集可
        assertThat(meta.canEdit()).isTrue();
    }
}
