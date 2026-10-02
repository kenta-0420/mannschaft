package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogMediaUploadUrlRequest;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ブログ動画の保存scope、別記事指定、draft→記事への一度限りの紐付けを検証する。 */
@ExtendWith(MockitoExtension.class)
class BlogMediaAclServiceTest {
    @Mock private BlogMediaUploadRepository mediaRepository;
    @Mock private BlogPostRepository postRepository;
    @Mock private AccessControlService access;
    @Mock private BlogBodyMediaResolver body;
    @InjectMocks private BlogMediaAclService service;

    @Test
    void 別テナントのblogPostIdを自テナントscopeで申告しても拒否する() {
        when(postRepository.findById(100L)).thenReturn(Optional.of(post(99L)));
        assertThatThrownBy(() -> service.resolveUploadScope(1L,
                new BlogMediaUploadUrlRequest("VIDEO", "video/mp4", 100L, "TEAM", 12L, 100L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 未保存記事でも投稿先への所属を確認する() {
        service.resolveUploadScope(1L,
                new BlogMediaUploadUrlRequest("VIDEO", "video/mp4", 100L, "TEAM", 12L, null));
        verify(access).checkMembership(1L, 12L, "TEAM");
    }

    @Test
    void 個人scopeへ他ユーザーIDは申告できない() {
        assertThatThrownBy(() -> service.resolveUploadScope(1L,
                new BlogMediaUploadUrlRequest("VIDEO", "video/mp4", 100L, "PERSONAL", 2L, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 動画claimは保存済みメディアIDとscopeから復元する() {
        var media = media().toBuilder().blogPostId(100L).build();
        when(mediaRepository.findByS3Key(media.getS3Key())).thenReturn(Optional.of(media));
        when(postRepository.findById(100L)).thenReturn(Optional.of(post(12L)));
        var target = service.resolveMultipartTarget(media.getS3Key(), 1L).orElseThrow();
        assertThat(target.scope()).isEqualTo(StorageAclScope.team(12L));
        assertThat(target.parent().key()).isEqualTo("7");
        assertThat(target.binding().key()).isEqualTo("7");
    }

    @Test
    void 保存scopeと親記事scopeが違えばcompleteを拒否する() {
        var media = media().toBuilder().blogPostId(100L).build();
        when(mediaRepository.findByS3Key(media.getS3Key())).thenReturn(Optional.of(media));
        when(postRepository.findById(100L)).thenReturn(Optional.of(post(99L)));
        assertThatThrownBy(() -> service.resolveMultipartTarget(media.getS3Key(), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void draft動画は同じscopeの記事へ紐付きbindingIDを維持する() {
        var media = media();
        var targetBefore = BlogMediaAclService.targetOf(media);
        when(body.extractR2Keys(any())).thenReturn(List.of(media.getS3Key()));
        when(mediaRepository.findForPostBinding(any())).thenReturn(List.of(media));
        service.bindBodyMedia(post(12L), 1L);
        assertThat(media.getBlogPostId()).isEqualTo(100L);
        assertThat(BlogMediaAclService.targetOf(media)).isEqualTo(targetBefore);
    }

    @Test
    void draft動画を別scopeの記事へ紐付けられない() {
        var media = media();
        when(body.extractR2Keys(any())).thenReturn(List.of(media.getS3Key()));
        when(mediaRepository.findForPostBinding(any())).thenReturn(List.of(media));
        assertThatThrownBy(() -> service.bindBodyMedia(post(99L), 1L)).isInstanceOf(BusinessException.class);
        assertThat(media.getBlogPostId()).isNull();
    }

    @Test
    void 同一scopeでも他記事に束縛済みの動画は付け替えられない() {
        var media = media().toBuilder().blogPostId(101L).build();
        when(body.extractR2Keys(any())).thenReturn(List.of(media.getS3Key()));
        when(mediaRepository.findForPostBinding(any())).thenReturn(List.of(media));
        assertThatThrownBy(() -> service.bindBodyMedia(post(12L), 1L)).isInstanceOf(BusinessException.class);
        assertThat(media.getBlogPostId()).isEqualTo(101L);
    }

    @Test
    void 旧未検証scopeをキーから推定してclaimしない() {
        assertThatThrownBy(() -> BlogMediaAclService.targetOf(media().toBuilder()
                .scopeType(null).scopeId(null).build())).isInstanceOf(BusinessException.class);
    }

    private BlogPostEntity post(Long teamId) {
        return BlogPostEntity.builder().id(100L).authorId(1L).teamId(teamId).body("本文").build();
    }

    private BlogMediaUploadEntity media() {
        return BlogMediaUploadEntity.builder().id(7L).uploaderId(1L).scopeType("TEAM").scopeId(12L)
                .s3Key("blog/TEAM/12/v.mp4").mediaType("VIDEO").build();
    }
}
