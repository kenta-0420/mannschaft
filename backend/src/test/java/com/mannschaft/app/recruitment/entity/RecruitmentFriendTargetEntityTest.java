package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecruitmentFriendTargetEntity} 単体テスト。F22.1 市・部隊1。
 *
 * <p>ファクトリメソッドが {@code target_kind} と参照列（folder_id / team_id）の
 * 整合（DB の {@code ck_rft_kind} CHECK 制約と同一）を強制することを検証する。</p>
 */
@DisplayName("RecruitmentFriendTargetEntity 単体テスト")
class RecruitmentFriendTargetEntityTest {

    private static final Long LISTING_ID = 100L;
    private static final Long FOLDER_ID = 200L;
    private static final Long TEAM_ID = 300L;

    @Nested
    @DisplayName("ofAllFriends")
    class OfAllFriends {

        @Test
        @DisplayName("ALL_FRIENDS は folder_id / team_id ともに NULL になる")
        void ALL_FRIENDSはfolderIdとteamIdがともにNULL() {
            RecruitmentFriendTargetEntity target = RecruitmentFriendTargetEntity.ofAllFriends(LISTING_ID);

            assertThat(target.getListingId()).isEqualTo(LISTING_ID);
            assertThat(target.getTargetKind()).isEqualTo(RecruitmentFriendTargetKind.ALL_FRIENDS);
            assertThat(target.getFolderId()).isNull();
            assertThat(target.getTeamId()).isNull();
        }

        @Test
        @DisplayName("listing_id が null なら例外")
        void listingIdがnullなら例外() {
            assertThatThrownBy(() -> RecruitmentFriendTargetEntity.ofAllFriends(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ofFolder")
    class OfFolder {

        @Test
        @DisplayName("FOLDER は folder_id を持ち team_id は NULL")
        void FOLDERはfolderIdを持ちteamIdはNULL() {
            RecruitmentFriendTargetEntity target = RecruitmentFriendTargetEntity.ofFolder(LISTING_ID, FOLDER_ID);

            assertThat(target.getTargetKind()).isEqualTo(RecruitmentFriendTargetKind.FOLDER);
            assertThat(target.getFolderId()).isEqualTo(FOLDER_ID);
            assertThat(target.getTeamId()).isNull();
        }

        @Test
        @DisplayName("folder_id が null なら例外")
        void folderIdがnullなら例外() {
            assertThatThrownBy(() -> RecruitmentFriendTargetEntity.ofFolder(LISTING_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ofTeam")
    class OfTeam {

        @Test
        @DisplayName("TEAM は team_id を持ち folder_id は NULL")
        void TEAMはteamIdを持ちfolderIdはNULL() {
            RecruitmentFriendTargetEntity target = RecruitmentFriendTargetEntity.ofTeam(LISTING_ID, TEAM_ID);

            assertThat(target.getTargetKind()).isEqualTo(RecruitmentFriendTargetKind.TEAM);
            assertThat(target.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(target.getFolderId()).isNull();
        }

        @Test
        @DisplayName("team_id が null なら例外")
        void teamIdがnullなら例外() {
            assertThatThrownBy(() -> RecruitmentFriendTargetEntity.ofTeam(LISTING_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("onCreate（createdAt 補完）")
    class OnCreate {

        @Test
        @DisplayName("createdAt 未設定時は永続化前フックで現在時刻が補完される")
        void createdAt未設定時はフックで補完される() {
            RecruitmentFriendTargetEntity target = RecruitmentFriendTargetEntity.ofAllFriends(LISTING_ID);
            assertThat(target.getCreatedAt()).isNull();

            target.onCreate();

            assertThat(target.getCreatedAt()).isNotNull();
        }
    }
}
