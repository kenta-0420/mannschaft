package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.role.service.RoleService;
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
 * {@link BlogAuthorFavoriteResolver} 単体テスト。
 *
 * <p>著者アバターが {@link MediaUrlResolver} 解決後の署名付き表示URLとして返ることと、
 * 本人・公開プロフィール・同一チーム所属による可視性判定を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogAuthorFavoriteResolver 単体テスト")
class BlogAuthorFavoriteResolverTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private BlogAuthorFavoriteResolver resolver;

    @Test
    @DisplayName("resolveAll: avatarUrl は生R2キーでなく署名付き表示URLを返す")
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

    @Test
    @DisplayName("resolveAll: 公開プロフィール無効かつ無関係な他ユーザーはUNAVAILABLEを返す")
    void resolveAll_nonPublicOtherUser_returnsUnavailable() {
        UserEntity user = UserEntity.builder()
                .email("author@example.com")
                .displayName("著者太郎")
                .avatarUrl("user/5/avatar/raw.png")
                .publicProfileEnabled(false)
                .build();
        ReflectionTestUtils.setField(user, "id", 5L);

        given(userRepository.findByIdIn(any())).willReturn(List.of(user));
        // 閲覧者と同一チームに所属していない
        given(roleService.existsSharedTeam(99L, 5L)).willReturn(false);

        // 閲覧者は著者本人ではない
        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("5"), 99L);

        FavoriteEntityMetaDto meta = result.get("5");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
    }

    @Test
    @DisplayName("resolveAll: 公開プロフィール無効でも閲覧者と同一チームの他ユーザーは身元を返す")
    void resolveAll_nonPublicSharedTeamUser_returnsIdentity() {
        UserEntity user = UserEntity.builder()
                .email("author@example.com")
                .displayName("著者太郎")
                .avatarUrl("user/5/avatar/raw.png")
                .publicProfileEnabled(false)
                .build();
        ReflectionTestUtils.setField(user, "id", 5L);

        given(userRepository.findByIdIn(any())).willReturn(List.of(user));
        // 閲覧者と同一チームに所属している
        given(roleService.existsSharedTeam(99L, 5L)).willReturn(true);
        given(mediaUrlResolver.resolve("user/5/avatar/raw.png"))
                .willReturn("https://cdn.example/signed/user-5");

        // 閲覧者は著者本人ではない
        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("5"), 99L);

        FavoriteEntityMetaDto meta = result.get("5");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.AVAILABLE);
        assertThat(meta.displayName()).isEqualTo("著者太郎");
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/user-5");
        // 他者のプロフィールは編集不可
        assertThat(meta.canEdit()).isFalse();
    }

    @Test
    @DisplayName("resolveAll: 公開プロフィール有効な他ユーザーは従来どおり身元を返す")
    void resolveAll_publicOtherUser_returnsIdentity() {
        UserEntity user = UserEntity.builder()
                .email("author@example.com")
                .displayName("著者太郎")
                .avatarUrl("user/5/avatar/raw.png")
                .publicProfileEnabled(true)
                .build();
        ReflectionTestUtils.setField(user, "id", 5L);

        given(userRepository.findByIdIn(any())).willReturn(List.of(user));
        given(mediaUrlResolver.resolve("user/5/avatar/raw.png"))
                .willReturn("https://cdn.example/signed/user-5");

        // 閲覧者は著者本人ではない
        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("5"), 99L);

        FavoriteEntityMetaDto meta = result.get("5");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.AVAILABLE);
        assertThat(meta.displayName()).isEqualTo("著者太郎");
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/user-5");
        // 他者のプロフィールは編集不可
        assertThat(meta.canEdit()).isFalse();
    }
}
