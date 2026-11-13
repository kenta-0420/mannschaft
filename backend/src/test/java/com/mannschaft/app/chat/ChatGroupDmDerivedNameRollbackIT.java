package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.chat.service.ChatChannelFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * CMP-261113-0110 AC-16: 作成系（invite-to-zimmer / convert-to-group）で導出処理が例外を投げたら全ロールバックする試練（red 先行）。
 *
 * <p><b>注入点</b>: 導出名の参加者ユーザー解決は、一覧の N+1 回避（AC-11）と同じく {@link UserRepository#findAllById}
 * による一括取得で行う契約とし、その呼出を {@link MockitoSpyBean} で例外化する。DB・認可・業務 Bean はモックしない
 * （spy は既定で実処理へ委譲する）。注入点に実際に到達したことを {@link #tripped} で確認する
 * （到達していない rollback テストは緑でも無意味なため）。</p>
 *
 * <p><b>試験層</b>（TEST_CONVENTION.md §3.1.2 内部 Service・TX）: tx を持たない入口 {@link ChatChannelFacade} を
 * 外側の TX なしで呼び、実 MySQL の実 rollback を別 TX の再読込で観測する。fixture は明示 commit し、所有行だけ片付ける。
 * Bean 差し替えで context が分かれるため、AC-1〜15 の {@code ChatGroupDmDerivedNameContractIT} とは分けた。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261113-0110 AC-16 導出失敗時の作成系全ロールバック（試練）")
class ChatGroupDmDerivedNameRollbackIT extends AbstractMySqlIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

    @MockitoSpyBean private UserRepository userRepository;
    @Autowired private ChatChannelFacade channelFacade;
    @Autowired private ChatChannelRepository channelRepository;
    @Autowired private ChatChannelMemberRepository memberRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    private final List<Long> ownedUsers = new ArrayList<>();
    private final Set<Long> ownedChannels = new LinkedHashSet<>();
    private final AtomicInteger tripped = new AtomicInteger();

    private Long me;
    private Long alice;
    private Long bob;
    private Long kabine;
    private List<Long> kabineMessageIds;

    @BeforeEach
    void fixtureを別TXでコミットする() {
        tx(() -> {
            me = user("me", "巻戻ミー");
            alice = user("alice", "巻戻アリス");
            bob = user("bob", "巻戻ボブ");
            kabine = channelRepository.save(ChatChannelEntity.builder()
                    .channelType(ChannelType.DM).createdBy(me).build()).getId();
            ownedChannels.add(kabine);
            memberRepository.save(ChatChannelMemberEntity.builder()
                    .channelId(kabine).userId(me).role(ChannelMemberRole.OWNER).joinedAt(T0).build());
            memberRepository.save(ChatChannelMemberEntity.builder()
                    .channelId(kabine).userId(alice).role(ChannelMemberRole.MEMBER).joinedAt(T0.plusSeconds(1)).build());
            kabineMessageIds = List.of(
                    messageRepository.save(ChatMessageEntity.builder()
                            .channelId(kabine).senderId(me).body("巻戻試練1").build()).getId(),
                    messageRepository.save(ChatMessageEntity.builder()
                            .channelId(kabine).senderId(alice).body("巻戻試練2").build()).getId());
            return null;
        });
    }

    @AfterEach
    void 所有fixtureだけ片付ける() {
        reset(userRepository);
        if (ownedUsers.isEmpty()) {
            return;
        }
        String userIn = inClause(ownedUsers);
        ownedChannels.addAll(jdbc.queryForList(
                "SELECT DISTINCT channel_id FROM chat_channel_members WHERE user_id IN " + userIn, Long.class));
        ownedChannels.addAll(jdbc.queryForList(
                "SELECT id FROM chat_channels WHERE created_by IN " + userIn, Long.class));
        if (!ownedChannels.isEmpty()) {
            String chIn = inClause(new ArrayList<>(ownedChannels));
            jdbc.update("DELETE FROM chat_messages WHERE channel_id IN " + chIn);
            jdbc.update("DELETE FROM chat_channel_members WHERE channel_id IN " + chIn);
            jdbc.update("DELETE FROM chat_channels WHERE id IN " + chIn);
        }
        jdbc.update("DELETE FROM users WHERE id IN " + userIn);
    }

    @Test
    @DisplayName("AC-16: invite-to-zimmer で導出が例外を投げたら新 Zimmer・メンバー・コピー履歴が残らない")
    void ac16_inviteToZimmerの導出失敗で全ロールバック() throws Exception {
        long channelsBefore = countChannelsCreatedBy(me);
        long bobMemberRowsBefore = countMemberRows(bob);
        InviteToZimmerRequest request = mapper.readValue(
                "{\"userIds\":[" + bob + "],\"shareHistory\":true}", InviteToZimmerRequest.class);

        armDerivationFailure();
        Throwable thrown = catchThrowable(() -> channelFacade.inviteToZimmer(kabine, me, request));
        reset(userRepository);

        assertThat(tripped.get()).as("導出の注入点（UserRepository#findAllById）に到達したこと").isPositive();
        assertThat(thrown).as("導出の例外が作成処理の失敗として呼出元へ伝わる").isNotNull();
        assertThat(countChannelsCreatedBy(me)).as("新 Zimmer が残らない").isEqualTo(channelsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_channels WHERE channel_type = 'GROUP_DM' AND created_by = ?", Long.class, me))
                .isZero();
        assertThat(countMemberRows(bob)).as("招待者のメンバー行が残らない").isEqualTo(bobMemberRowsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_messages WHERE forwarded_from_id IN " + inClause(kabineMessageIds), Long.class))
                .as("コピー履歴が残らない").isZero();
        assertThat(jdbc.queryForObject("SELECT channel_type FROM chat_channels WHERE id = ?", String.class, kabine))
                .as("元 Kabine は DM のまま").isEqualTo("DM");
    }

    @Test
    @DisplayName("AC-16: convert-to-group で導出が例外を投げたら元 DM は DM のまま")
    void ac16_convertToGroupの導出失敗で元DMのまま() {
        armDerivationFailure();
        Throwable thrown = catchThrowable(() -> channelFacade.convertToGroup(kabine, me));
        reset(userRepository);

        assertThat(tripped.get()).as("導出の注入点（UserRepository#findAllById）に到達したこと").isPositive();
        assertThat(thrown).as("導出の例外が変換処理の失敗として呼出元へ伝わる").isNotNull();
        assertThat(jdbc.queryForObject("SELECT channel_type FROM chat_channels WHERE id = ?", String.class, kabine))
                .as("元 DM は DM のまま").isEqualTo("DM");
        assertThat(jdbc.queryForObject("SELECT name FROM chat_channels WHERE id = ?", String.class, kabine)).isNull();
    }

    /** 導出のための参加者一括解決（findAllById）を、到達を数えつつ例外化する。 */
    private void armDerivationFailure() {
        tripped.set(0);
        doAnswer(invocation -> {
            tripped.incrementAndGet();
            throw new IllegalStateException("AC-16 検証用: 導出名の参加者解決を強制失敗させる");
        }).when(userRepository).findAllById(any());
    }

    private long countChannelsCreatedBy(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_channels WHERE created_by = ?", Long.class, userId);
    }

    private long countMemberRows(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_channel_members WHERE user_id = ?", Long.class, userId);
    }

    private Long user(String label, String displayName) {
        Long id = userRepository.save(UserEntity.builder()
                .email("cmp0110-rb-" + label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com")
                .lastName("試練").firstName(label).displayName(displayName).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        ownedUsers.add(id);
        return id;
    }

    private static String inClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.append(')').toString();
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
}
