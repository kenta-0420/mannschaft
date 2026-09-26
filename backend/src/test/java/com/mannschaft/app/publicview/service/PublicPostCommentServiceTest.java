package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.publicview.dto.PublicPostCommentResponse;
import com.mannschaft.app.publicview.entity.PublicPostCommentEntity;
import com.mannschaft.app.publicview.repository.PublicPostCommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicPostCommentService の著者一括取得")
class PublicPostCommentServiceTest {

    private static final Long POST_ID = 10L;

    @Mock
    private PublicPostCommentRepository commentRepository;
    @Mock
    private BlogPostRepository blogPostRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PublicPostCommentService service;

    @Test
    @DisplayName("空ページでは著者を取得しない")
    void getComments_emptyPage_doesNotQueryAuthors() {
        Pageable pageable = PageRequest.of(1, 20);
        givenPublicPost();
        given(commentRepository.findActiveByPostId(POST_ID, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<PublicPostCommentResponse> result = service.getComments(POST_ID, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isZero();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("複数コメントは著者IDを重複排除して一括取得し、順序とページ情報を維持する")
    void getComments_multipleComments_batchesDistinctAuthorsAndKeepsPage() {
        Pageable pageable = PageRequest.of(2, 3);
        PublicPostCommentEntity first = comment(101L, "first");
        PublicPostCommentEntity second = comment(202L, "second");
        PublicPostCommentEntity third = comment(101L, "third");
        givenPublicPost();
        given(commentRepository.findActiveByPostId(POST_ID, pageable))
                .willReturn(new PageImpl<>(List.of(first, second, third), pageable, 10));
        given(userRepository.findNameMapByIdIn(any()))
                .willReturn(Map.of(101L, "Alice", 202L, "Bob"));

        Page<PublicPostCommentResponse> result = service.getComments(POST_ID, pageable);

        ArgumentCaptor<Collection<Long>> authorIds = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).findNameMapByIdIn(authorIds.capture());
        verify(userRepository, never()).findById(any());
        assertThat(authorIds.getValue()).containsExactlyInAnyOrder(101L, 202L);
        assertThat(result.getContent()).extracting(PublicPostCommentResponse::content)
                .containsExactly("first", "second", "third");
        assertThat(result.getContent()).extracting(PublicPostCommentResponse::authorDisplayName)
                .containsExactly("Alice", "Bob", "Alice");
        assertThat(result.getNumber()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(3);
        assertThat(result.getTotalElements()).isEqualTo(10);
    }

    @Test
    @DisplayName("取得できない著者は従来どおり退会済みユーザーとして返す")
    void getComments_missingAuthor_usesExistingFallback() {
        Pageable pageable = PageRequest.of(0, 20);
        givenPublicPost();
        given(commentRepository.findActiveByPostId(POST_ID, pageable))
                .willReturn(new PageImpl<>(List.of(comment(404L, "orphan")), pageable, 1));
        given(userRepository.findNameMapByIdIn(any())).willReturn(Map.of());

        Page<PublicPostCommentResponse> result = service.getComments(POST_ID, pageable);

        assertThat(result.getContent()).singleElement()
                .extracting(PublicPostCommentResponse::authorDisplayName)
                .isEqualTo("退会済みユーザー");
    }

    @Test
    @DisplayName("著者一括取得の失敗は部分応答にせず伝播する")
    void getComments_authorBatchLookupFails_propagatesFailure() {
        Pageable pageable = PageRequest.of(0, 20);
        RuntimeException failure = new RuntimeException("database unavailable");
        givenPublicPost();
        given(commentRepository.findActiveByPostId(POST_ID, pageable))
                .willReturn(new PageImpl<>(List.of(comment(101L, "comment")), pageable, 1));
        given(userRepository.findNameMapByIdIn(any())).willThrow(failure);

        assertThatThrownBy(() -> service.getComments(POST_ID, pageable)).isSameAs(failure);
    }

    private void givenPublicPost() {
        BlogPostEntity post = BlogPostEntity.builder()
                .visibility(Visibility.PUBLIC)
                .status(PostStatus.PUBLISHED)
                .publicVisible(true)
                .build();
        given(blogPostRepository.findById(POST_ID)).willReturn(java.util.Optional.of(post));
    }

    private PublicPostCommentEntity comment(Long authorId, String content) {
        PublicPostCommentEntity comment = PublicPostCommentEntity.create(POST_ID, authorId, content, null);
        comment.setId(UUID.randomUUID());
        return comment;
    }
}
