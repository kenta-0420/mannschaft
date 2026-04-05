package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.PlatformAnnouncementEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnouncementFeedbackMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("AnnouncementFeedbackMapper 単体テスト")
class AnnouncementFeedbackMapperTest {

    private final AnnouncementFeedbackMapper mapper = Mappers.getMapper(AnnouncementFeedbackMapper.class);

    // ========================================
    // PlatformAnnouncementEntity → AnnouncementResponse
    // ========================================

    @Nested
    @DisplayName("toAnnouncementResponse")
    class ToAnnouncementResponse {

        @Test
        @DisplayName("正常系: エンティティがDTOに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            PlatformAnnouncementEntity entity = PlatformAnnouncementEntity.builder()
                    .title("メンテナンスのお知らせ")
                    .body("システムメンテナンスを実施します。")
                    .priority("HIGH")
                    .targetScope("ALL")
                    .isPinned(true)
                    .expiresAt(LocalDateTime.of(2026, 12, 31, 23, 59))
                    .createdBy(1L)
                    .build();

            // When
            AnnouncementResponse response = mapper.toAnnouncementResponse(entity);

            // Then
            assertThat(response.getTitle()).isEqualTo("メンテナンスのお知らせ");
            assertThat(response.getBody()).isEqualTo("システムメンテナンスを実施します。");
            assertThat(response.getPriority()).isEqualTo("HIGH");
            assertThat(response.getTargetScope()).isEqualTo("ALL");
            assertThat(response.getIsPinned()).isTrue();
            assertThat(response.getCreatedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常系: publishedAtなし未公開状態が変換される")
        void 変換_未公開_publishedAtNull() {
            // Given
            PlatformAnnouncementEntity entity = PlatformAnnouncementEntity.builder()
                    .title("未公開お知らせ")
                    .body("未公開の本文")
                    .priority("NORMAL")
                    .targetScope("ORGANIZATION")
                    .isPinned(false)
                    .createdBy(2L)
                    .build();

            // When
            AnnouncementResponse response = mapper.toAnnouncementResponse(entity);

            // Then
            assertThat(response.getTitle()).isEqualTo("未公開お知らせ");
            assertThat(response.getPublishedAt()).isNull();
            assertThat(response.getIsPinned()).isFalse();
        }

        @Test
        @DisplayName("正常系: publish後にpublishedAtが変換される")
        void 変換_公開済み_publishedAtあり() {
            // Given
            PlatformAnnouncementEntity entity = PlatformAnnouncementEntity.builder()
                    .title("公開済みお知らせ")
                    .body("公開済みの本文")
                    .priority("NORMAL")
                    .targetScope("ALL")
                    .isPinned(false)
                    .createdBy(1L)
                    .build();
            entity.publish();

            // When
            AnnouncementResponse response = mapper.toAnnouncementResponse(entity);

            // Then
            assertThat(response.getTitle()).isEqualTo("公開済みお知らせ");
            assertThat(response.getPublishedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("toAnnouncementResponseList")
    class ToAnnouncementResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<PlatformAnnouncementEntity> entities = List.of(
                    PlatformAnnouncementEntity.builder()
                            .title("お知らせ1").body("本文1").priority("HIGH")
                            .targetScope("ALL").isPinned(true).createdBy(1L).build(),
                    PlatformAnnouncementEntity.builder()
                            .title("お知らせ2").body("本文2").priority("NORMAL")
                            .targetScope("TEAM").isPinned(false).createdBy(2L).build()
            );

            // When
            List<AnnouncementResponse> responses = mapper.toAnnouncementResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTitle()).isEqualTo("お知らせ1");
            assertThat(responses.get(0).getPriority()).isEqualTo("HIGH");
            assertThat(responses.get(1).getTitle()).isEqualTo("お知らせ2");
            assertThat(responses.get(1).getPriority()).isEqualTo("NORMAL");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<AnnouncementResponse> responses = mapper.toAnnouncementResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // FeedbackSubmissionEntity → FeedbackResponse
    // ========================================

    @Nested
    @DisplayName("toFeedbackResponse")
    class ToFeedbackResponse {

        @Test
        @DisplayName("正常系: FeedbackSubmissionEntityがFeedbackResponseに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(10L)
                    .category("BUG")
                    .title("バグ報告")
                    .body("アプリがクラッシュします")
                    .isAnonymous(false)
                    .submittedBy(1L)
                    .status(FeedbackStatus.OPEN)
                    .build();

            // When
            FeedbackResponse response = mapper.toFeedbackResponse(entity);

            // Then
            assertThat(response.getScopeType()).isEqualTo("TEAM");
            assertThat(response.getScopeId()).isEqualTo(10L);
            assertThat(response.getCategory()).isEqualTo("BUG");
            assertThat(response.getTitle()).isEqualTo("バグ報告");
            assertThat(response.getBody()).isEqualTo("アプリがクラッシュします");
            assertThat(response.getIsAnonymous()).isFalse();
            assertThat(response.getSubmittedBy()).isEqualTo(1L);
            // voteCount は ignore なので null
            assertThat(response.getVoteCount()).isNull();
        }

        @Test
        @DisplayName("正常系: 匿名投稿が変換される")
        void 変換_匿名_isAnonymousTrue() {
            // Given
            FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                    .scopeType("ORGANIZATION")
                    .scopeId(5L)
                    .category("FEATURE_REQUEST")
                    .title("機能要望")
                    .body("ダークモードが欲しい")
                    .isAnonymous(true)
                    .submittedBy(2L)
                    .status(FeedbackStatus.OPEN)
                    .build();

            // When
            FeedbackResponse response = mapper.toFeedbackResponse(entity);

            // Then
            assertThat(response.getIsAnonymous()).isTrue();
            assertThat(response.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(response.getTitle()).isEqualTo("機能要望");
        }

        @Test
        @DisplayName("正常系: 管理者回答済みフィードバックが変換される")
        void 変換_回答済み_adminResponseが変換される() {
            // Given
            FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(10L)
                    .category("QUESTION")
                    .title("質問")
                    .body("使い方を教えてください")
                    .isAnonymous(false)
                    .submittedBy(3L)
                    .status(FeedbackStatus.OPEN)
                    .build();
            entity.respond("回答です", 100L, true);

            // When
            FeedbackResponse response = mapper.toFeedbackResponse(entity);

            // Then
            assertThat(response.getAdminResponse()).isEqualTo("回答です");
            assertThat(response.getRespondedBy()).isEqualTo(100L);
            assertThat(response.getRespondedAt()).isNotNull();
            assertThat(response.getIsPublicResponse()).isTrue();
        }

        @Test
        @DisplayName("正常系: CLOSEDステータスが変換される")
        void 変換_CLOSEDステータス_String変換() {
            // Given
            FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(1L)
                    .category("OTHER")
                    .title("その他")
                    .body("内容")
                    .isAnonymous(false)
                    .submittedBy(1L)
                    .status(FeedbackStatus.OPEN)
                    .build();
            entity.changeStatus(FeedbackStatus.CLOSED);

            // When
            FeedbackResponse response = mapper.toFeedbackResponse(entity);

            // Then
            assertThat(response.getTitle()).isEqualTo("その他");
        }
    }

    @Nested
    @DisplayName("toFeedbackResponseList")
    class ToFeedbackResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<FeedbackSubmissionEntity> entities = List.of(
                    FeedbackSubmissionEntity.builder()
                            .scopeType("TEAM").scopeId(1L).category("BUG")
                            .title("バグ1").body("内容1").isAnonymous(false)
                            .submittedBy(1L).status(FeedbackStatus.OPEN).build(),
                    FeedbackSubmissionEntity.builder()
                            .scopeType("TEAM").scopeId(1L).category("FEATURE_REQUEST")
                            .title("要望1").body("内容2").isAnonymous(true)
                            .submittedBy(2L).status(FeedbackStatus.OPEN).build()
            );

            // When
            List<FeedbackResponse> responses = mapper.toFeedbackResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTitle()).isEqualTo("バグ1");
            assertThat(responses.get(1).getTitle()).isEqualTo("要望1");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<FeedbackResponse> responses = mapper.toFeedbackResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // AdminActionTemplateEntity → ActionTemplateResponse
    // ========================================

    @Nested
    @DisplayName("toActionTemplateResponse")
    class ToActionTemplateResponse {

        @Test
        @DisplayName("正常系: エンティティがDTOに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            AdminActionTemplateEntity entity = AdminActionTemplateEntity.builder()
                    .name("警告テンプレート")
                    .actionType("WARNING")
                    .reason("規約違反")
                    .templateText("あなたの行為は規約に違反しています。")
                    .isDefault(false)
                    .createdBy(100L)
                    .build();

            // When
            ActionTemplateResponse response = mapper.toActionTemplateResponse(entity);

            // Then
            assertThat(response.getName()).isEqualTo("警告テンプレート");
            assertThat(response.getActionType()).isEqualTo("WARNING");
            assertThat(response.getReason()).isEqualTo("規約違反");
            assertThat(response.getTemplateText()).isEqualTo("あなたの行為は規約に違反しています。");
            assertThat(response.getIsDefault()).isFalse();
            assertThat(response.getCreatedBy()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: isDefaultがtrueの場合も変換される")
        void 変換_isDefaultTrue_trueが返る() {
            // Given
            AdminActionTemplateEntity entity = AdminActionTemplateEntity.builder()
                    .name("デフォルト警告")
                    .actionType("BAN")
                    .reason(null)
                    .templateText("アカウントをBANします。")
                    .isDefault(true)
                    .createdBy(1L)
                    .build();

            // When
            ActionTemplateResponse response = mapper.toActionTemplateResponse(entity);

            // Then
            assertThat(response.getName()).isEqualTo("デフォルト警告");
            assertThat(response.getIsDefault()).isTrue();
            assertThat(response.getReason()).isNull();
        }
    }

    @Nested
    @DisplayName("toActionTemplateResponseList")
    class ToActionTemplateResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<AdminActionTemplateEntity> entities = List.of(
                    AdminActionTemplateEntity.builder()
                            .name("テンプレートA").actionType("WARNING").reason("理由A")
                            .templateText("テキストA").isDefault(false).createdBy(1L).build(),
                    AdminActionTemplateEntity.builder()
                            .name("テンプレートB").actionType("BAN").reason("理由B")
                            .templateText("テキストB").isDefault(true).createdBy(2L).build()
            );

            // When
            List<ActionTemplateResponse> responses = mapper.toActionTemplateResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getName()).isEqualTo("テンプレートA");
            assertThat(responses.get(0).getActionType()).isEqualTo("WARNING");
            assertThat(responses.get(1).getName()).isEqualTo("テンプレートB");
            assertThat(responses.get(1).getActionType()).isEqualTo("BAN");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<ActionTemplateResponse> responses = mapper.toActionTemplateResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }
}
