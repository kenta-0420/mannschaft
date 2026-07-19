package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogBodyMediaBackfillService} の純ユニットテスト（Mockito・Docker 不要）。
 *
 * <p>受け入れ条件 AC-B5（破損データの補正・リビジョン同時補正）と
 * AC-B6（補正後キーの実在検証）に対応する。</p>
 *
 * <p><b>最大の落とし穴</b>: {@code blog_post_revisions.body} に本文の副本が存在するため、
 * {@code blog_posts} だけ補正すると「版を戻した瞬間に破損が復活する」。
 * 本テストはその同時補正を明示的に要求する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogBodyMediaBackfillService — 破損した本文キーの補正")
class BlogBodyMediaBackfillServiceTest {

    @Mock
    private BlogPostRepository blogPostRepository;
    @Mock
    private BlogPostRevisionRepository blogPostRevisionRepository;
    @Mock
    private BlogMediaUploadRepository blogMediaUploadRepository;

    @InjectMocks
    private BlogBodyMediaBackfillService service;

    private static final String GOOD_KEY = "blog/TEAM/12/aaaaaaaa-1111.png";
    private static final String BROKEN_IMAGE_BODY = "![写真](/blog/TEAM/12/aaaaaaaa-1111.png)";
    private static final String FIXED_IMAGE_BODY = "![写真](blog/TEAM/12/aaaaaaaa-1111.png)";

    private static BlogPostEntity post(Long id, String body) {
        BlogPostEntity e = BlogPostEntity.builder()
                .teamId(12L)
                .title("タイトル")
                .body(body)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static BlogPostRevisionEntity revision(Long id, Long postId, String body) {
        BlogPostRevisionEntity e = BlogPostRevisionEntity.builder()
                .blogPostId(postId)
                .revisionNumber(1)
                .title("タイトル")
                .body(body)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    /** 補正後キーが blog_media_uploads に実在する、という既定スタブ。 */
    private void stubKeyExists(String key) {
        lenient().when(blogMediaUploadRepository.findByS3Key(key))
                .thenReturn(Optional.of(BlogMediaUploadEntity.builder().s3Key(key).build()));
    }

    // ========================================
    // AC-B5: 破損データの補正（純関数部分）
    // ========================================

    @Nested
    @DisplayName("AC-B5: 絶対パス化したキーを相対キーへ補正する")
    class Normalize {

        @Test
        @DisplayName("AC-B5-1: 画像記法 ](/blog/ が ](blog/ へ補正される")
        void 画像の絶対パスキーが補正される() {
            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys(BROKEN_IMAGE_BODY))
                    .isEqualTo(FIXED_IMAGE_BODY);
        }

        @Test
        @DisplayName("AC-B5-2: 動画記法 src=\"/blog/ が src=\"blog/ へ補正される")
        void 動画の絶対パスキーが補正される() {
            String broken = "<video src=\"/blog/TEAM/12/bbbbbbbb-2222.mp4\" controls></video>";
            String fixed = "<video src=\"blog/TEAM/12/bbbbbbbb-2222.mp4\" controls></video>";

            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys(broken))
                    .isEqualTo(fixed);
        }

        @Test
        @DisplayName("AC-B5-3: 既に正常なキーは変更しない（冪等）")
        void 正常なキーは変更されない() {
            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys(FIXED_IMAGE_BODY))
                    .as("二重適用しても壊れないこと")
                    .isEqualTo(FIXED_IMAGE_BODY);
        }

        @Test
        @DisplayName("AC-B5-4: blog 以外の絶対パスリンクは巻き込まない")
        void 無関係な絶対パスは巻き込まない() {
            String body = "[規約](/terms) と ![外部](https://example.com/a.png)";

            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys(body))
                    .isEqualTo(body);
        }

        @Test
        @DisplayName("AC-B5-5: null / 空文字は安全に扱う")
        void nullと空文字は安全() {
            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys(null)).isNull();
            assertThat(BlogBodyMediaBackfillService.normalizeLegacyKeys("")).isEmpty();
        }
    }

    // ========================================
    // AC-B5: リビジョンも同時補正（最重要）
    // ========================================

    @Nested
    @DisplayName("AC-B5: blog_post_revisions も同時に補正する")
    class BackfillBothTables {

        @Test
        @DisplayName("AC-B5-6: blog_posts の破損本文が補正されて保存される")
        void 記事本文が補正保存される() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, BROKEN_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());
            stubKeyExists(GOOD_KEY);

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository).save(captor.capture());

            assertThat(captor.getValue().getBody()).isEqualTo(FIXED_IMAGE_BODY);
            assertThat(result.postsFixed()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-B5-7: blog_post_revisions の破損本文も補正されて保存される（版を戻しても復活しない）")
        void リビジョン本文も補正保存される() {
            given(blogPostRepository.findAll()).willReturn(List.of());
            given(blogPostRevisionRepository.findAll())
                    .willReturn(List.of(revision(10L, 1L, BROKEN_IMAGE_BODY)));
            stubKeyExists(GOOD_KEY);

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            ArgumentCaptor<BlogPostRevisionEntity> captor =
                    ArgumentCaptor.forClass(BlogPostRevisionEntity.class);
            verify(blogPostRevisionRepository).save(captor.capture());

            assertThat(captor.getValue().getBody())
                    .as("リビジョンを補正しないと版を戻した瞬間に破損が復活する")
                    .isEqualTo(FIXED_IMAGE_BODY);
            assertThat(result.revisionsFixed()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-B5-8: 記事とリビジョンの両方が同一実行で補正される")
        void 記事とリビジョンが同時補正される() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, BROKEN_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll())
                    .willReturn(List.of(revision(10L, 1L, BROKEN_IMAGE_BODY)));
            stubKeyExists(GOOD_KEY);

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            assertThat(result.postsFixed()).isEqualTo(1);
            assertThat(result.revisionsFixed()).isEqualTo(1);
            verify(blogPostRepository).save(org.mockito.ArgumentMatchers.any(BlogPostEntity.class));
            verify(blogPostRevisionRepository)
                    .save(org.mockito.ArgumentMatchers.any(BlogPostRevisionEntity.class));
        }

        @Test
        @DisplayName("AC-B5-9: 破損していない記事は保存しない（無駄な UPDATE を出さない）")
        void 破損していない記事は保存されない() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, FIXED_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            verify(blogPostRepository, never()).save(org.mockito.ArgumentMatchers.any());
            assertThat(result.postsFixed()).isZero();
        }

        @Test
        @DisplayName("AC-B5-10: 本文が空/null の記事でも落ちない")
        void 本文が空やnullでも落ちない() {
            given(blogPostRepository.findAll())
                    .willReturn(List.of(post(1L, ""), post(2L, null)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            assertThat(result.postsFixed()).isZero();
            verify(blogPostRepository, never()).save(org.mockito.ArgumentMatchers.any());
        }
    }

    // ========================================
    // AC-B6: 補正後キーの実在検証
    // ========================================

    @Nested
    @DisplayName("AC-B6: 補正後の r2Key が blog_media_uploads.s3_key に実在するか検証する")
    class KeyExistenceVerification {

        @Test
        @DisplayName("AC-B6-1: 補正後キーの実在を blog_media_uploads へ問い合わせる")
        void 補正後キーの実在を検証する() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, BROKEN_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());
            stubKeyExists(GOOD_KEY);

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            verify(blogMediaUploadRepository).findByS3Key(GOOD_KEY);
            assertThat(result.unknownKeys())
                    .as("実在するキーは unknownKeys に含めないこと")
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-B6-2: 実在しないキーは unknownKeys として報告される（黙って捨てない）")
        void 実在しないキーは報告される() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, BROKEN_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());
            given(blogMediaUploadRepository.findByS3Key(anyString())).willReturn(Optional.empty());

            BlogBodyMediaBackfillService.BackfillResult result = service.backfillLegacyAbsoluteKeys();

            assertThat(result.unknownKeys())
                    .as("R2 に実体が無いキーは運用へ報告すること")
                    .containsExactly(GOOD_KEY);
        }

        @Test
        @DisplayName("AC-B6-3: 実在しなくても本文の補正自体は行う（リンク切れは残すが形式は正す）")
        void 実在しなくても補正自体は行う() {
            given(blogPostRepository.findAll()).willReturn(List.of(post(1L, BROKEN_IMAGE_BODY)));
            given(blogPostRevisionRepository.findAll()).willReturn(List.of());
            given(blogMediaUploadRepository.findByS3Key(anyString())).willReturn(Optional.empty());

            service.backfillLegacyAbsoluteKeys();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository).save(captor.capture());
            assertThat(captor.getValue().getBody()).isEqualTo(FIXED_IMAGE_BODY);
        }
    }
}
