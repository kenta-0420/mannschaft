package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRevisionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link KbRevisionService} 単体テスト。
 *
 * <p>toBuilder → INSERT 化バグ根治の回帰テスト。
 * {@code restoreRevision} で {@code findById} の同一インスタンスが {@code save} に渡り
 * id を保持することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KbRevisionService 単体テスト（toBuilder 根治 回帰）")
class KbRevisionServiceTest {

    private static final Long PAGE_ID = 10L;
    private static final Long REVISION_ID = 5L;
    private static final Long USER_ID = 100L;
    private static final String ADMIN_ROLE = "ADMIN";

    @Mock
    private KbPageRepository pageRepository;
    @Mock
    private KbPageRevisionRepository revisionRepository;

    @InjectMocks
    private KbRevisionService service;

    private KbPageEntity pageWithId(Long id) {
        KbPageEntity page = KbPageEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .title("元タイトル")
                .slug("test-page")
                .body("元本文")
                .icon(null)
                .accessLevel(PageAccessLevel.ALL_MEMBERS)
                .status(PageStatus.PUBLISHED)
                .createdBy(USER_ID)
                .path("/" + id)
                .build();
        ReflectionTestUtils.setField(page, "id", id);
        ReflectionTestUtils.setField(page, "version", 0L);
        return page;
    }

    private KbPageRevisionEntity revisionWithId(Long id, Long pageId) {
        KbPageRevisionEntity revision = KbPageRevisionEntity.builder()
                .kbPageId(pageId)
                .revisionNumber(1)
                .title("リビジョンタイトル")
                .body("リビジョン本文")
                .editorId(USER_ID)
                .build();
        ReflectionTestUtils.setField(revision, "id", id);
        return revision;
    }

    // ====================================================================
    // restoreRevision
    // ====================================================================

    @Nested
    @DisplayName("restoreRevision — toBuilder 根治 回帰")
    class RestoreRevision {

        @Test
        @DisplayName("正常系: findById で取得した同一インスタンスが save に渡り id を保持する")
        void 正常系_同一インスタンスかつidを保持してsaveに渡る() {
            KbPageEntity page = pageWithId(PAGE_ID);
            KbPageRevisionEntity targetRevision = revisionWithId(REVISION_ID, PAGE_ID);

            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            given(revisionRepository.findByIdAndKbPageId(REVISION_ID, PAGE_ID))
                    .willReturn(Optional.of(targetRevision));
            given(revisionRepository.countByKbPageId(PAGE_ID)).willReturn(2);
            given(revisionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(pageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.restoreRevision(PAGE_ID, REVISION_ID, USER_ID, ADMIN_ROLE);

            ArgumentCaptor<KbPageEntity> captor = ArgumentCaptor.forClass(KbPageEntity.class);
            verify(pageRepository).save(captor.capture());

            KbPageEntity saved = captor.getValue();
            // 同一インスタンス（findById の managed entity そのもの）であることを確認
            assertThat(saved).isSameAs(page);
            // id 保持を明示確認（INSERT 化していない）
            assertThat(saved.getId()).isEqualTo(PAGE_ID);
            // リビジョンの内容が反映されていること
            assertThat(saved.getTitle()).isEqualTo("リビジョンタイトル");
            assertThat(saved.getBody()).isEqualTo("リビジョン本文");
            assertThat(saved.getLastEditedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("リビジョン存在しない場合 → KB_007")
        void リビジョン不存在_KB_007() {
            KbPageEntity page = pageWithId(PAGE_ID);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            // findByIdAndKbPageId が empty を返した時点で KB_007 例外が投げられる
            // → その後の countByKbPageId / revisionRepository.save は呼ばれないためスタブ不要
            given(revisionRepository.findByIdAndKbPageId(REVISION_ID, PAGE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.restoreRevision(PAGE_ID, REVISION_ID, USER_ID, ADMIN_ROLE))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_007);
        }

        @Test
        @DisplayName("権限なし（作成者でも管理者でもない）→ KB_002")
        void 権限なし_KB_002() {
            KbPageEntity page = pageWithId(PAGE_ID);
            // 作成者は USER_ID=100、リクエストユーザーは別人
            ReflectionTestUtils.setField(page, "createdBy", 999L);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));

            assertThatThrownBy(
                    () -> service.restoreRevision(PAGE_ID, REVISION_ID, USER_ID, "MEMBER"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_002);
        }
    }
}
