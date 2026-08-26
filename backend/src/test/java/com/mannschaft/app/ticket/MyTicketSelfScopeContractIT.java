package com.mannschaft.app.ticket;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可漏れ監査 第2波（金銭）: 顧客向けチケットの自己スコープ 2 エンドポイントの API 契約テスト。
 *
 * <p><b>対象 EP</b></p>
 * <ul>
 *   <li>{@code GET /api/v1/teams/{teamId}/my-tickets} — マイチケット一覧</li>
 *   <li>{@code GET /api/v1/teams/{teamId}/my-tickets/widget} — チケット残数ウィジェット</li>
 * </ul>
 *
 * <p><b>保証する内容</b>: いずれも購入者を指定する引数を持たず、絞り込みキーが
 * 「認証主体 × パスのチーム」に固定される。したがって他顧客の購入したチケット（残数・有効期限・
 * 購入者名）は一覧にもウィジェットにも混入しない。チーム ID を差し替えても、返るのは
 * 常に自分がそのチームで購入した分だけである。</p>
 *
 * <p>ID を指定して単票を引く詳細・領収書・QR は {@code TicketAccessGuard} 経由で所有者一致を
 * 強制しており（{@code ticket/service/TicketAccessGuard.java}）、認可番人の判定でも
 * 認可シグナルありと認識されるため本テストの対象外。</p>
 *
 * <p><b>未認証（401）経路について</b>: {@code addFilters = false} のため未認証リクエストの経路は存在しない。
 * 未認証の遮断は {@code SecurityConfig} の {@code anyRequest().authenticated()} が担保する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("マイチケット 自己スコープ API 契約テスト（認可根治 第2波）")
class MyTicketSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketProductRepository ticketProductRepository;

    @Autowired
    private TicketBookRepository ticketBookRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    /** 回数券を購入した顧客。 */
    private Long buyerId;
    /** 何も購入していない別の顧客（他顧客の購入分が見えてはならない）。 */
    private Long outsiderId;

    private Long bookId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("チケット認可テスト チーム " + System.nanoTime());
        buyerId = insertUser("ticket-buyer@example.com");
        outsiderId = insertUser("ticket-outsider@example.com");

        TicketProductEntity product = ticketProductRepository.save(TicketProductEntity.builder()
                .teamId(teamId)
                .name("契約テスト用 回数券")
                .totalTickets(10)
                .price(1000)
                .validityDays(90)
                .createdBy(buyerId)
                .build());

        TicketBookEntity book = ticketBookRepository.save(TicketBookEntity.builder()
                .teamId(teamId)
                .productId(product.getId())
                .userId(buyerId)
                .totalTickets(10)
                .usedTickets(0)
                .status(TicketBookStatus.ACTIVE)
                .purchasedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(90))
                .build());
        bookId = book.getId();

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("マイチケット一覧には他顧客の購入分が混入しない")
    void マイチケット一覧は購入者本人に閉じる() throws Exception {
        setAuthentication(buyerId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(bookId))
                .andExpect(jsonPath("$.data[0].userId").value(buyerId));

        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("status=ALL を指定しても他顧客の購入分は混入しない")
    void 全件指定でも購入者本人に閉じる() throws Exception {
        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets", teamId).param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        setAuthentication(buyerId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets", teamId).param("status", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("チケットウィジェットには他顧客の残数が混入しない")
    void ウィジェットは購入者本人に閉じる() throws Exception {
        setAuthentication(buyerId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/widget", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCount").value(1))
                .andExpect(jsonPath("$.data.tickets[0].bookId").value(bookId));

        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/widget", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeCount").value(0))
                .andExpect(jsonPath("$.data.tickets.length()").value(0));
    }

    // ═════════════════════════════════════════════════════════════════════
    // seed ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'チケット契約', 'テスト', 'チケット契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
