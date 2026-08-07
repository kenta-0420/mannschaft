package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.ticket.repository.TicketBookRepository;
import com.mannschaft.app.ticket.repository.TicketProductRepository;
import com.mannschaft.app.ticket.service.TicketBookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 認可根治戦役 束A（Wave 0）: {@link MyTicketController} の {@code return 1L} スタブ除去検証。
 *
 * <p>従来 {@code getCurrentUserId()} が常に {@code 1L} を返すスタブだったため、認証済みの別ユーザーが
 * {@code GET /api/v1/teams/{teamId}/my-tickets} を叩くと <b>userId=1 のチケット</b>が返る BOLA だった。
 * スタブを撤去し {@code SecurityUtils.getCurrentUserId()}（SecurityContext の principal＝userId）へ
 * 置換することで、サービス層へ渡す userId がログイン主体の ID になることを担保する（AC-0-3）。</p>
 *
 * <p>{@code SecurityUtils.getCurrentUserId()} は {@code SecurityContextHolder}（スレッドローカル）から
 * principal を読むため、Spring コンテキスト無しの純ユニットで SecurityContext を差し込めば検証できる。</p>
 */
@DisplayName("束A: MyTicketController の userId は SecurityContext 由来（return 1L スタブ除去）")
class MyTicketControllerAuthzTest {

    private final TicketBookService bookService = mock(TicketBookService.class);
    private final MyTicketController controller = new MyTicketController(
            bookService,
            mock(PdfGeneratorService.class),
            mock(NameResolverService.class),
            mock(TicketProductRepository.class),
            mock(TicketBookRepository.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "n/a",
                        AuthorityUtils.createAuthorityList("ROLE_MEMBER")));
    }

    @Test
    @DisplayName("AC-0-3: ログイン主体(userId=42)で叩くと service へ 42 が渡る（1L 固定でない）")
    void getMyTickets_passesAuthenticatedUserId_notHardcodedOne() {
        loginAs(42L);
        when(bookService.getMyTickets(eq(42L), eq(42L), isNull())).thenReturn(List.of());

        controller.getMyTickets(42L, null);

        // スタブ時代は常に 1L が渡っていた。除去後は SecurityContext の 42 が渡ること。
        verify(bookService).getMyTickets(eq(42L), eq(42L), isNull());
    }

    /**
     * 認可根治戦役 Wave6 ロットG: {@code MyTicketController#getWidget}（{@code @AuthorizedInService} 付与済み）
     * について、{@code bookService.getWidget} へは常にログイン主体の userId が渡り、
     * 他ユーザーの userId が紛れ込まないことを検証する。
     */
    @Test
    @DisplayName("MyTicketController#getWidget: ログイン主体(userId=42)で叩くと service へ 42 が渡る")
    void getWidget_passesAuthenticatedUserId() {
        loginAs(42L);
        when(bookService.getWidget(eq(42L), eq(42L))).thenReturn(null);

        controller.getWidget(42L);

        verify(bookService).getWidget(eq(42L), eq(42L));
    }
}
