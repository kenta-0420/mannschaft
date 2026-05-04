package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.dto.PinnedCardListResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardReferenceResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.8.1 Phase 3 {@link MyPinnedCardsService} 単体テスト。
 *
 * <p>カバー範囲（設計書 §10.1）:</p>
 * <ul>
 *   <li>横断取得・空リスト</li>
 *   <li>cursor ページネーション（次ページ取得・next_cursor null）</li>
 *   <li>参照先解決（type 別の navigate_to 生成）</li>
 *   <li>権限喪失参照先 → is_accessible=false、navigate_to=null</li>
 *   <li>論理削除参照先 → is_deleted=true、snapshot 表示</li>
 *   <li>URL カードの navigate_to=url 値</li>
 *   <li>MEMO/SECTION_HEADER → reference=null</li>
 *   <li>SQL 数の上限検証（mock 呼び出し回数）</li>
 *   <li>cursor エンコード/デコードの可逆性</li>
 *   <li>不正な cursor → BusinessException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyPinnedCardsService 単体テスト")
class MyPinnedCardsServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long BOARD_ID = 10L;
    private static final Long BOARD_ID_2 = 11L;

    @Mock
    private CorkboardCardRepository cardRepository;

    @Mock
    private CorkboardRepository boardRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Spy
    private ReferenceTypeResolver referenceTypeResolver = new ReferenceTypeResolver();

    @Spy
    private AccessControlDispatcher accessControlDispatcher = new AccessControlDispatcher();

    @InjectMocks
    private MyPinnedCardsService service;

    private CorkboardEntity personalBoard;
    private CorkboardEntity personalBoard2;

    @BeforeEach
    void setUp() {
        personalBoard = buildBoard(BOARD_ID, "仕事メモ");
        personalBoard2 = buildBoard(BOARD_ID_2, "プロジェクト資料");
    }

    private CorkboardEntity buildBoard(Long id, String name) {
        CorkboardEntity board = CorkboardEntity.builder()
                .scopeType("PERSONAL")
                .ownerId(USER_ID)
                .name(name)
                .build();
        setEntityId(board, id);
        return board;
    }

    private CorkboardCardEntity buildCard(Long id, Long boardId, String cardType,
                                            String referenceType, Long referenceId,
                                            String url, LocalDateTime pinnedAt) {
        CorkboardCardEntity card = CorkboardCardEntity.builder()
                .corkboardId(boardId)
                .cardType(cardType)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .url(url)
                .colorLabel("YELLOW")
                .createdBy(USER_ID)
                .isArchived(false)
                .isPinned(true)
                .pinnedAt(pinnedAt)
                .userNote("メモ")
                .contentSnapshot("スナップショットタイトル\n本文の抜粋")
                .build();
        setEntityId(card, id);
        return card;
    }

    /** {@code BaseEntity#id} は {@code @GeneratedValue} のため、テスト用にリフレクションで設定する。 */
    private void setEntityId(Object entity, Long id) {
        try {
            Field idField = findIdField(entity.getClass());
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Field findIdField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField("id");
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id");
    }

    @Test
    @DisplayName("空リスト → items=[]、nextCursor=null、totalCount=0")
    void 空リスト() {
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(0);

        PinnedCardListResponse response = service.list(USER_ID, null, null);

        assertThat(response.getItems()).isEmpty();
        assertThat(response.getNextCursor()).isNull();
        assertThat(response.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("正常系: 横断取得 + ボード名解決（保守的フォールバック適用で TIMELINE_POST は isAccessible=false）")
    void 横断取得_正常() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 3, 14, 23);
        CorkboardCardEntity card1 = buildCard(345L, BOARD_ID, "REFERENCE", "TIMELINE_POST", 9876L, null, now);
        CorkboardCardEntity card2 = buildCard(312L, BOARD_ID_2, "MEMO", null, null, null, now.minusDays(1));

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card1, card2));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(2);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard, personalBoard2));

        PinnedCardListResponse response = service.list(USER_ID, 20, null);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getTotalCount()).isEqualTo(2L);
        assertThat(response.getNextCursor()).isNull(); // ページ無し

        PinnedCardResponse item1 = response.getItems().get(0);
        assertThat(item1.getCardId()).isEqualTo(345L);
        assertThat(item1.getCorkboardName()).isEqualTo("仕事メモ");
        assertThat(item1.getColorLabel()).isEqualTo("YELLOW");
        assertThat(item1.getReference()).isNotNull();
        assertThat(item1.getReference().getType()).isEqualTo("TIMELINE_POST");
        // 保守的フォールバック適用中: isAccessible=false、navigateTo=null
        assertThat(item1.getReference().getIsAccessible()).isFalse();
        assertThat(item1.getReference().getNavigateTo()).isNull();
        assertThat(item1.getReference().getIsDeleted()).isFalse();

        // MEMO カードは reference = null
        PinnedCardResponse item2 = response.getItems().get(1);
        assertThat(item2.getReference()).isNull();
        assertThat(item2.getCorkboardName()).isEqualTo("プロジェクト資料");
    }

    @Test
    @DisplayName("URL カード → reference.type=URL、navigate_to=URL 値")
    void URLカード() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity url = buildCard(500L, BOARD_ID, "URL", "URL", null,
                "https://booking.example.com/rooms", now);
        // OGP メタを設定
        ReflectionTestUtils.setField(url, "ogTitle", "会議室予約 - Example Corp");
        ReflectionTestUtils.setField(url, "ogImageUrl", "https://booking.example.com/og.png");

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(url));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        PinnedCardListResponse response = service.list(USER_ID, 20, null);

        PinnedCardReferenceResponse ref = response.getItems().get(0).getReference();
        assertThat(ref.getType()).isEqualTo("URL");
        assertThat(ref.getId()).isNull();
        assertThat(ref.getUrl()).isEqualTo("https://booking.example.com/rooms");
        assertThat(ref.getNavigateTo()).isEqualTo("https://booking.example.com/rooms");
        assertThat(ref.getOgTitle()).isEqualTo("会議室予約 - Example Corp");
        assertThat(ref.getOgImageUrl()).isEqualTo("https://booking.example.com/og.png");
        assertThat(ref.getIsAccessible()).isTrue();
    }

    @Test
    @DisplayName("未対応 type の参照 → is_accessible=false、navigate_to=null、snapshot あり")
    void 未対応タイプ_フォールバック() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity card = buildCard(600L, BOARD_ID, "REFERENCE", "UNKNOWN_FUTURE_TYPE", 999L, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getIsAccessible()).isFalse();
        assertThat(ref.getNavigateTo()).isNull();
        assertThat(ref.getSnapshotTitle()).isEqualTo("スナップショットタイトル");
    }

    @Test
    @DisplayName("権限喪失参照先 → is_accessible=false、navigate_to=null（snapshot は表示）")
    void 権限喪失() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity card = buildCard(700L, BOARD_ID, "REFERENCE", "TIMELINE_POST", 9000L, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));
        // 保守的フォールバック適用中はディスパッチャがデフォルトで「全 false」を返すので追加スタブ不要

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getType()).isEqualTo("TIMELINE_POST");
        assertThat(ref.getIsAccessible()).isFalse();
        assertThat(ref.getNavigateTo()).isNull();
        assertThat(ref.getSnapshotTitle()).isEqualTo("スナップショットタイトル");
    }

    @Test
    @DisplayName("論理削除参照先 → is_deleted=true、snapshot 表示")
    void 論理削除参照先() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity card = buildCard(800L, BOARD_ID, "REFERENCE", "TIMELINE_POST", 9001L, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));
        // 削除済みとして上書き
        given(accessControlDispatcher.filterDeleted(eq("TIMELINE_POST"), any()))
                .willReturn(Set.of(9001L));

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getIsDeleted()).isTrue();
        assertThat(ref.getSnapshotTitle()).isEqualTo("スナップショットタイトル");
    }

    @Test
    @DisplayName("MEMO カード → reference=null、SECTION_HEADER カード → reference=null")
    void メモカード_セクション() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity memo = buildCard(901L, BOARD_ID, "MEMO", null, null, null, now);
        CorkboardCardEntity section = buildCard(902L, BOARD_ID, "SECTION_HEADER", null, null, null, now.minusMinutes(1));

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(memo, section));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(2);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        List<PinnedCardResponse> items = service.list(USER_ID, 20, null).getItems();
        assertThat(items.get(0).getReference()).isNull();
        assertThat(items.get(1).getReference()).isNull();
    }

    @Test
    @DisplayName("ページネーション: limit=2 + 取得 3 件 → items 2 件、nextCursor 生成")
    void ページネーション_次ページあり() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 3, 12, 0);
        CorkboardCardEntity c1 = buildCard(1L, BOARD_ID, "MEMO", null, null, null, base);
        CorkboardCardEntity c2 = buildCard(2L, BOARD_ID, "MEMO", null, null, null, base.minusMinutes(1));
        CorkboardCardEntity c3 = buildCard(3L, BOARD_ID, "MEMO", null, null, null, base.minusMinutes(2));

        // limit + 1 = 3 件返る → hasNext = true、items は 2 件
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(c1, c2, c3));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(10);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        PinnedCardListResponse response = service.list(USER_ID, 2, null);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getNextCursor()).isNotNull();
        assertThat(response.getTotalCount()).isEqualTo(10L);

        // cursor デコードして 2 件目（最後の含まれた件）の情報を保持しているか
        MyPinnedCardsService.CursorPosition pos = service.decodeCursor(response.getNextCursor());
        assertThat(pos.id()).isEqualTo(2L);
        assertThat(pos.pinnedAt()).isEqualTo(base.minusMinutes(1));
    }

    @Test
    @DisplayName("ページネーション: cursor 渡し → 復号した値が repository に伝わる")
    void ページネーション_cursor渡し() {
        LocalDateTime cursorPinnedAt = LocalDateTime.of(2026, 5, 3, 10, 0);
        Long cursorId = 99L;
        String cursor = service.encodeCursor(cursorPinnedAt, cursorId);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(cursorPinnedAt), eq(cursorId), any(Pageable.class)))
                .willReturn(List.of());
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(0);

        service.list(USER_ID, 20, cursor);

        ArgumentCaptor<LocalDateTime> pinnedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(cardRepository).findPinnedCardsForUser(eq(USER_ID),
                pinnedAtCaptor.capture(), idCaptor.capture(), any(Pageable.class));

        assertThat(pinnedAtCaptor.getValue()).isEqualTo(cursorPinnedAt);
        assertThat(idCaptor.getValue()).isEqualTo(cursorId);
    }

    @Test
    @DisplayName("不正な cursor → BusinessException (COMMON_001)")
    void 不正cursor() {
        assertThatThrownBy(() -> service.list(USER_ID, 20, "not-a-valid-base64-json"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("limit 上限丸め: 100 渡されても最大 50 件取得（pageable size = 51）")
    void limit上限丸め() {
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(0);

        service.list(USER_ID, 100, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository).findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(MyPinnedCardsService.MAX_LIMIT + 1);
    }

    @Test
    @DisplayName("limit null/0/負 → デフォルト 20 件取得（pageable size = 21）")
    void limitデフォルト() {
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of());
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(0);

        service.list(USER_ID, null, null);
        service.list(USER_ID, 0, null);
        service.list(USER_ID, -10, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(cardRepository, times(3)).findPinnedCardsForUser(any(), any(), any(), pageableCaptor.capture());
        for (Pageable p : pageableCaptor.getAllValues()) {
            assertThat(p.getPageSize()).isEqualTo(MyPinnedCardsService.DEFAULT_LIMIT + 1);
        }
    }

    @Test
    @DisplayName("SQL 数の上限: 1 リクエストでカード一覧 1 + ボード名 1 + 件数 1 = 3 リポジトリ呼び出し")
    void SQL数の上限() {
        LocalDateTime now = LocalDateTime.now();
        List<CorkboardCardEntity> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(buildCard((long) i, BOARD_ID, "REFERENCE", "TIMELINE_POST",
                    (long) (1000 + i), null, now.minusMinutes(i)));
        }
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(cards);
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(5);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        service.list(USER_ID, 20, null);

        // メインクエリ・件数・ボード名で最大 3 回。type 別解決は MVP では DB アクセスなし。
        verify(cardRepository, times(1)).findPinnedCardsForUser(any(), any(), any(), any());
        verify(cardRepository, times(1)).countPinnedByOwnerIdAndScopePersonal(USER_ID);
        verify(boardRepository, times(1)).findAllById(any());
    }

    @Test
    @DisplayName("cursor のエンコード/デコードは可逆である")
    void cursor可逆性() {
        LocalDateTime t = LocalDateTime.of(2026, 5, 3, 14, 23, 45);
        Long id = 12345L;
        String encoded = service.encodeCursor(t, id);

        MyPinnedCardsService.CursorPosition decoded = service.decodeCursor(encoded);
        assertThat(decoded.pinnedAt()).isEqualTo(t);
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("保守的フォールバック: 全 type で isAccessible=false が返る（共通 ContentVisibilityResolver 完成までの繋ぎ）")
    void 保守的フォールバック適用() {
        LocalDateTime now = LocalDateTime.now();
        // 対応 type を網羅して並べる
        CorkboardCardEntity timeline = buildCard(11L, BOARD_ID, "REFERENCE", "TIMELINE_POST", 100L, null, now);
        CorkboardCardEntity bulletin = buildCard(12L, BOARD_ID, "REFERENCE", "BULLETIN_THREAD", 200L, null, now.minusMinutes(1));
        CorkboardCardEntity blog = buildCard(13L, BOARD_ID, "REFERENCE", "BLOG_POST", 300L, null, now.minusMinutes(2));
        CorkboardCardEntity file = buildCard(14L, BOARD_ID, "REFERENCE", "FILE", 400L, null, now.minusMinutes(3));
        CorkboardCardEntity team = buildCard(15L, BOARD_ID, "REFERENCE", "TEAM", 500L, null, now.minusMinutes(4));
        CorkboardCardEntity org = buildCard(16L, BOARD_ID, "REFERENCE", "ORGANIZATION", 600L, null, now.minusMinutes(5));
        CorkboardCardEntity event = buildCard(17L, BOARD_ID, "REFERENCE", "EVENT", 700L, null, now.minusMinutes(6));
        CorkboardCardEntity document = buildCard(18L, BOARD_ID, "REFERENCE", "DOCUMENT", 800L, null, now.minusMinutes(7));

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(timeline, bulletin, blog, file, team, org, event, document));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(8);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        List<PinnedCardResponse> items = service.list(USER_ID, 20, null).getItems();

        // 全ての対応 type について isAccessible=false / navigateTo=null を確認
        for (PinnedCardResponse item : items) {
            PinnedCardReferenceResponse ref = item.getReference();
            assertThat(ref).isNotNull();
            assertThat(ref.getIsAccessible())
                    .as("type=%s は保守的フォールバック中 isAccessible=false", ref.getType())
                    .isFalse();
            assertThat(ref.getNavigateTo())
                    .as("type=%s は保守的フォールバック中 navigateTo=null", ref.getType())
                    .isNull();
        }
    }

    @Test
    @DisplayName("URL カードは保守的フォールバックの影響を受けず navigate_to=url 値のまま")
    void URLカード_フォールバック影響なし() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardCardEntity url = buildCard(501L, BOARD_ID, "URL", "URL", null,
                "https://example.com/external", now);
        CorkboardCardEntity timeline = buildCard(502L, BOARD_ID, "REFERENCE", "TIMELINE_POST", 9999L, null, now.minusMinutes(1));

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(url, timeline));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(2);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));

        List<PinnedCardResponse> items = service.list(USER_ID, 20, null).getItems();

        // URL カード: navigateTo=url 値、isAccessible=true
        PinnedCardReferenceResponse urlRef = items.get(0).getReference();
        assertThat(urlRef.getType()).isEqualTo("URL");
        assertThat(urlRef.getIsAccessible()).isTrue();
        assertThat(urlRef.getNavigateTo()).isEqualTo("https://example.com/external");

        // 比較: TIMELINE_POST はフォールバックで false
        PinnedCardReferenceResponse postRef = items.get(1).getReference();
        assertThat(postRef.getType()).isEqualTo("TIMELINE_POST");
        assertThat(postRef.getIsAccessible()).isFalse();
        assertThat(postRef.getNavigateTo()).isNull();
    }

    @Test
    @DisplayName("CHAT_MESSAGE: 閲覧権限あり時 navigate_to=/chat/channels/{channelId}?messageId={id}")
    void CHAT_MESSAGE_navigate_to() {
        LocalDateTime now = LocalDateTime.now();
        Long messageId = 5555L;
        Long channelId = 42L;
        CorkboardCardEntity card = buildCard(900L, BOARD_ID, "REFERENCE", "CHAT_MESSAGE", messageId, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));
        // 閲覧権限あり扱いに上書き（保守的フォールバック中も dispatcher.filterAccessible を直接スタブで上書き）
        given(accessControlDispatcher.filterAccessible(eq(USER_ID), eq("CHAT_MESSAGE"), any()))
                .willReturn(Set.of(messageId));
        // ChatMessage バッチ取得
        ChatMessageEntity msg = ChatMessageEntity.builder()
                .channelId(channelId)
                .build();
        setEntityId(msg, messageId);
        given(chatMessageRepository.findAllById(any())).willReturn(List.of(msg));

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getType()).isEqualTo("CHAT_MESSAGE");
        assertThat(ref.getIsAccessible()).isTrue();
        assertThat(ref.getIsDeleted()).isFalse();
        assertThat(ref.getNavigateTo()).isEqualTo("/chat/channels/42?messageId=5555");
    }

    @Test
    @DisplayName("CHAT_MESSAGE: 該当メッセージが存在しない（論理削除等）→ is_deleted=true、navigate_to=null")
    void CHAT_MESSAGE_メッセージ削除済() {
        LocalDateTime now = LocalDateTime.now();
        Long messageId = 7777L;
        CorkboardCardEntity card = buildCard(901L, BOARD_ID, "REFERENCE", "CHAT_MESSAGE", messageId, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));
        // 閲覧権限ありに上書きしても、メッセージ自体が見つからなければ is_deleted=true
        given(accessControlDispatcher.filterAccessible(eq(USER_ID), eq("CHAT_MESSAGE"), any()))
                .willReturn(Set.of(messageId));
        // findAllById は空 → channelId 解決できず
        given(chatMessageRepository.findAllById(any())).willReturn(List.of());

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getType()).isEqualTo("CHAT_MESSAGE");
        assertThat(ref.getIsDeleted()).isTrue();
        assertThat(ref.getNavigateTo()).isNull();
    }

    @Test
    @DisplayName("CHAT_MESSAGE: 保守的フォールバック中（isAccessible=false）は navigate_to=null（既定挙動）")
    void CHAT_MESSAGE_フォールバック中() {
        LocalDateTime now = LocalDateTime.now();
        Long messageId = 6666L;
        Long channelId = 11L;
        CorkboardCardEntity card = buildCard(902L, BOARD_ID, "REFERENCE", "CHAT_MESSAGE", messageId, null, now);

        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(personalBoard));
        // dispatcher は保守的フォールバックで全 false（明示スタブなし）
        ChatMessageEntity msg = ChatMessageEntity.builder()
                .channelId(channelId)
                .build();
        setEntityId(msg, messageId);
        given(chatMessageRepository.findAllById(any())).willReturn(List.of(msg));

        PinnedCardReferenceResponse ref = service.list(USER_ID, 20, null).getItems().get(0).getReference();
        assertThat(ref.getIsAccessible()).isFalse();
        assertThat(ref.getNavigateTo()).isNull();
    }

    @Test
    @DisplayName("ボード所有者でないボードのカードは boardName が null になる（防衛）")
    void 他人のボードは無視() {
        LocalDateTime now = LocalDateTime.now();
        CorkboardEntity foreignBoard = CorkboardEntity.builder()
                .scopeType("PERSONAL")
                .ownerId(999L)  // 他人
                .name("他人のボード")
                .build();
        setEntityId(foreignBoard, BOARD_ID);

        CorkboardCardEntity card = buildCard(1L, BOARD_ID, "MEMO", null, null, null, now);
        given(cardRepository.findPinnedCardsForUser(eq(USER_ID), eq(null), eq(null), any(Pageable.class)))
                .willReturn(List.of(card));
        given(cardRepository.countPinnedByOwnerIdAndScopePersonal(USER_ID)).willReturn(1);
        given(boardRepository.findAllById(any())).willReturn(List.of(foreignBoard));

        PinnedCardResponse item = service.list(USER_ID, 20, null).getItems().get(0);
        // 他人のボードは boardNameMap に入らない → null
        assertThat(item.getCorkboardName()).isNull();
    }
}
