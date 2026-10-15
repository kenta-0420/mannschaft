package com.mannschaft.app.social;

import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserSocialProfileEntity} の単体テスト。
 * updateProfile / changeHandle / deactivate / activate を検証する。
 */
@DisplayName("UserSocialProfileEntity 単体テスト")
class UserSocialProfileEntityTest {

    private UserSocialProfileEntity createProfile() {
        return UserSocialProfileEntity.builder()
                .userId(1L)
                .handle("testhandle")
                .displayName("テストユーザー")
                .bio("自己紹介文")
                .avatarUrl("https://example.com/avatar.jpg")
                .build();
    }

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("正常系: displayName、bio、avatarUrlが更新される")
        void 全フィールドが更新される() {
            // Given
            UserSocialProfileEntity profile = createProfile();

            // When
            profile.updateProfile("新しい名前", "新しい自己紹介", "https://example.com/new-avatar.jpg");

            // Then
            assertThat(profile.getDisplayName()).isEqualTo("新しい名前");
            assertThat(profile.getBio()).isEqualTo("新しい自己紹介");
            assertThat(profile.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.jpg");
        }

        @Test
        @DisplayName("正常系: nullを渡した場合は既存の値が保持される")
        void nullは既存の値を保持する() {
            // Given
            UserSocialProfileEntity profile = createProfile();

            // When
            profile.updateProfile(null, null, null);

            // Then
            assertThat(profile.getDisplayName()).isEqualTo("テストユーザー");
            assertThat(profile.getBio()).isEqualTo("自己紹介文");
            assertThat(profile.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        }

        @Test
        @DisplayName("正常系: displayNameのみ更新")
        void displayNameのみ更新() {
            // Given
            UserSocialProfileEntity profile = createProfile();

            // When
            profile.updateProfile("新しい名前", null, null);

            // Then
            assertThat(profile.getDisplayName()).isEqualTo("新しい名前");
            assertThat(profile.getBio()).isEqualTo("自己紹介文");
            assertThat(profile.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
        }
    }

    @Nested
    @DisplayName("changeHandle")
    class ChangeHandle {

        @Test
        @DisplayName("正常系: ハンドルが変更される")
        void ハンドルが変更される() {
            // Given
            UserSocialProfileEntity profile = createProfile();

            // When
            profile.changeHandle("newhandle");

            // Then
            assertThat(profile.getHandle()).isEqualTo("newhandle");
        }
    }

    @Nested
    @DisplayName("deactivate / activate")
    class DeactivateActivate {

        @Test
        @DisplayName("正常系: deactivateするとisActiveがfalseになる")
        void deactivateするとisActiveがfalse() {
            // Given
            UserSocialProfileEntity profile = createProfile();
            assertThat(profile.getIsActive()).isTrue();

            // When
            profile.deactivate();

            // Then
            assertThat(profile.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("正常系: activateするとisActiveがtrueになる")
        void activateするとisActiveがtrue() {
            // Given
            UserSocialProfileEntity profile = createProfile();
            profile.deactivate();
            assertThat(profile.getIsActive()).isFalse();

            // When
            profile.activate();

            // Then
            assertThat(profile.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("正常系: deactivate後にactivateすることで再有効化できる")
        void deactivate後にactivateで再有効化できる() {
            // Given
            UserSocialProfileEntity profile = createProfile();
            profile.deactivate();

            // When
            profile.activate();

            // Then
            assertThat(profile.getIsActive()).isTrue();
        }
    }
}
