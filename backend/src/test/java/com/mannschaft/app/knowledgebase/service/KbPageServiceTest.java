package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.PageStatus;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageFavoriteRepository;
import com.mannschaft.app.knowledgebase.repository.KbPagePinRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageQueryRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRevisionRepository;
import com.mannschaft.app.knowledgebase.repository.KbTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link KbPageService} 単体テスト。
 *
 * <p>toBuilder → INSERT 化バグ根治（#1643/#1648 キャンペーン）の回帰テスト。
 * 各更新メソッドで {@code findById} の同一インスタンスが {@code save} に渡ること（isSameAs）と
 * id 保持を明示検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KbPageService 単体テスト（toBuilder 根治 回帰）")
class KbPageServiceTest {

    private static final Long PAGE_ID = 10L;
    private static final Long SCOPE_ID = 1L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long USER_ID = 100L;
    private static final String USER_ROLE = "MEMBER";

    @Mock
    private KbPageRepository pageRepository;
    @Mock
    private KbPageRevisionRepository revisionRepository;
    @Mock
    private KbPageQueryRepository pageQueryRepository;
    @Mock
    private KbPagePinRepository pagePinRepository;
    @Mock
    private KbPageFavoriteRepository pageFavoriteRepository;
    @Mock
    private KbTemplateRepository templateRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private KbPageService service;

    /** id=PAGE_ID を持つテスト用エンティティを生成する。 */
    private KbPageEntity pageWithId(Long id) {
        KbPageEntity page = KbPageEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .title("テストページ")
                .slug("test-page")
                .body("本文")
                .icon(null)
                .accessLevel(PageAccessLevel.ALL_MEMBERS)
                .status(PageStatus.DRAFT)
                .createdBy(USER_ID)
                .path("/" + id)
                .build();
        // BaseEntity の id は @GeneratedValue で採番されるためリフレクションで設定
        ReflectionTestUtils.setField(page, "id", id);
        ReflectionTestUtils.setField(page, "version", 0L);
        return page;
    }

    // ====================================================================
    // updatePage
    // ====================================================================

    @Nested
    @DisplayName("updatePage — toBuilder 根治 回帰")
    class UpdatePage {

        @Test
        @DisplayName("正常系: findById で取得した同一インスタンスが save に渡り id を保持する")
        void 正常系_同一インスタンスかつidを保持してsaveに渡る() {
            KbPageEntity page = pageWithId(PAGE_ID);
            // DRAFT 状態のためリビジョン保存は呼ばれない（revisionRepository.countByKbPageId スタブ不要）
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            given(pageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            KbPageService.UpdateKbPageRequest req = new KbPageService.UpdateKbPageRequest(
                    "新タイトル", "新本文", null, PageAccessLevel.ADMIN_ONLY, 0L);
            service.updatePage(PAGE_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, USER_ROLE, req);

            ArgumentCaptor<KbPageEntity> captor = ArgumentCaptor.forClass(KbPageEntity.class);
            verify(pageRepository).save(captor.capture());

            KbPageEntity saved = captor.getValue();
            // 同一インスタンス（findById の managed entity そのもの）であることを確認
            assertThat(saved).isSameAs(page);
            // id 保持を明示確認（INSERT 化していない）
            assertThat(saved.getId()).isEqualTo(PAGE_ID);
            // 更新内容の反映
            assertThat(saved.getTitle()).isEqualTo("新タイトル");
            assertThat(saved.getBody()).isEqualTo("新本文");
            assertThat(saved.getAccessLevel()).isEqualTo(PageAccessLevel.ADMIN_ONLY);
            assertThat(saved.getLastEditedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("楽観的ロック version 不一致 → KB_006")
        void version不一致_KB_006() {
            KbPageEntity page = pageWithId(PAGE_ID);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));

            KbPageService.UpdateKbPageRequest req = new KbPageService.UpdateKbPageRequest(
                    "新タイトル", null, null, null, 99L); // version 不一致

            assertThatThrownBy(() -> service.updatePage(PAGE_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, USER_ROLE, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_006);
        }

        @Test
        @DisplayName("PUBLISHED ページ更新時はリビジョン保存を実行する")
        void PUBLISHED更新_リビジョン保存() {
            KbPageEntity page = pageWithId(PAGE_ID);
            // PUBLISHED 状態に変更
            page.applyStatus(PageStatus.PUBLISHED);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            given(revisionRepository.countByKbPageId(PAGE_ID)).willReturn(0);
            given(pageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(revisionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            KbPageService.UpdateKbPageRequest req = new KbPageService.UpdateKbPageRequest(
                    "更新タイトル", null, null, null, 0L);
            service.updatePage(PAGE_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, USER_ROLE, req);

            // リビジョン保存が呼ばれたことを確認
            verify(revisionRepository).save(any());
        }
    }

    // ====================================================================
    // publishPage
    // ====================================================================

    @Nested
    @DisplayName("publishPage — toBuilder 根治 回帰")
    class PublishPage {

        @Test
        @DisplayName("正常系: 同一インスタンスが save に渡り PUBLISHED に変更されかつ id を保持する")
        void 正常系_同一インスタンスかつidを保持してsaveに渡る() {
            KbPageEntity page = pageWithId(PAGE_ID);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            given(pageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.publishPage(PAGE_ID, SCOPE_TYPE, SCOPE_ID);

            ArgumentCaptor<KbPageEntity> captor = ArgumentCaptor.forClass(KbPageEntity.class);
            verify(pageRepository).save(captor.capture());

            KbPageEntity saved = captor.getValue();
            assertThat(saved).isSameAs(page);
            assertThat(saved.getId()).isEqualTo(PAGE_ID);
            assertThat(saved.getStatus()).isEqualTo(PageStatus.PUBLISHED);
        }

        @Test
        @DisplayName("既に PUBLISHED の場合 save を呼ばずそのまま返す")
        void 既にPUBLISHED_saveしない() {
            KbPageEntity page = pageWithId(PAGE_ID);
            page.applyStatus(PageStatus.PUBLISHED);
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));

            service.publishPage(PAGE_ID, SCOPE_TYPE, SCOPE_ID);

            // save が呼ばれていないことを確認
            verify(pageRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    // ====================================================================
    // archivePage
    // ====================================================================

    @Nested
    @DisplayName("archivePage — toBuilder 根治 回帰")
    class ArchivePage {

        @Test
        @DisplayName("正常系: 同一インスタンスが save に渡り ARCHIVED に変更されかつ id を保持する")
        void 正常系_同一インスタンスかつidを保持してsaveに渡る() {
            KbPageEntity page = pageWithId(PAGE_ID);
            page.applyStatus(PageStatus.PUBLISHED); // PUBLISHED からのみアーカイブ可
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));
            given(pageRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.archivePage(PAGE_ID, SCOPE_TYPE, SCOPE_ID);

            ArgumentCaptor<KbPageEntity> captor = ArgumentCaptor.forClass(KbPageEntity.class);
            verify(pageRepository).save(captor.capture());

            KbPageEntity saved = captor.getValue();
            assertThat(saved).isSameAs(page);
            assertThat(saved.getId()).isEqualTo(PAGE_ID);
            assertThat(saved.getStatus()).isEqualTo(PageStatus.ARCHIVED);
        }

        @Test
        @DisplayName("PUBLISHED 以外の場合 save を呼ばずそのまま返す")
        void DRAFT状態_saveしない() {
            KbPageEntity page = pageWithId(PAGE_ID); // DRAFT 初期状態
            given(pageRepository.findByIdAndDeletedAtIsNull(PAGE_ID)).willReturn(Optional.of(page));

            service.archivePage(PAGE_ID, SCOPE_TYPE, SCOPE_ID);

            verify(pageRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    // ====================================================================
    // createPage（path 更新時の toBuilder 根治）
    // ====================================================================

    @Nested
    @DisplayName("createPage — path 更新の toBuilder 根治 回帰")
    class CreatePage {

        @Test
        @DisplayName("正常系: save 後の path 更新でも同一インスタンスが再 save に渡りかつ id を保持する")
        void 正常系_path更新でも同一インスタンスかつid保持() {
            // 初回 save（id なし）に渡るエンティティと、その戻り値（id あり）を分離してモック
            KbPageEntity savedWithId = pageWithId(PAGE_ID);
            given(pageQueryRepository.existsBySlugAndScope(anyString(), anyString(), anyLong(), any()))
                    .willReturn(false);
            given(pageRepository.save(any()))
                    .willReturn(savedWithId) // 1回目 save（INSERT）→ id 付きエンティティを返す
                    .willAnswer(inv -> inv.getArgument(0)); // 2回目 save（path 更新）

            KbPageService.CreateKbPageRequest req = new KbPageService.CreateKbPageRequest(
                    "新ページ", "new-page", null, null, PageAccessLevel.ALL_MEMBERS, null, null);
            service.createPage(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // 2 回 save されること
            verify(pageRepository, org.mockito.Mockito.times(2)).save(any());

            // 2 回目の save（path 更新）にわたるエンティティが savedWithId と同一インスタンスであることを確認
            ArgumentCaptor<KbPageEntity> captor = ArgumentCaptor.forClass(KbPageEntity.class);
            verify(pageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
            KbPageEntity secondSave = captor.getAllValues().get(1);
            assertThat(secondSave).isSameAs(savedWithId);
            assertThat(secondSave.getId()).isEqualTo(PAGE_ID);
            // path が "/" + id で設定されること
            assertThat(secondSave.getPath()).isEqualTo("/" + PAGE_ID);
        }
    }
}
