package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.gdpr.PersonalData;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.16 予定コメントスレッド — GDPR 個人データ収集の契約テスト（試練・AC-35）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §3.3 / §9.4 AC-35。</p>
 *
 * <h2>なぜテストで固定しなければならないか</h2>
 * <p>{@code PersonalDataCoverageValidator} は {@code @PersonalData} の {@code category()} と
 * {@link PersonalDataCollector#getCategoryKeys()} を突合するが、<b>不足していても ERROR ログを出すだけで
 * 起動を止めない</b>。つまり注釈だけ付けて収集を実装しないと<b>静かにエクスポート漏れ</b>になる。
 * さらに既存の chat 側は注釈が {@code "chatMessages"}（キャメル）・収集キーが {@code "chat_messages"}
 * （スネーク）で食い違ったまま、収集本体は {@code "[]"} を返すスタブになっている。
 * 本機能は同じ轍を踏まないことをここで機械的に固定する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント GDPR 個人データ契約テスト（試練・AC-35）")
class ScheduleCommentPersonalDataContractIT extends AbstractMySqlIntegrationTest {

    /** {@code ScheduleCommentEntity} に付与された {@code @PersonalData} のカテゴリ。 */
    private static final String CATEGORY = "scheduleComments";

    @Autowired
    private PersonalDataCollector personalDataCollector;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        Long teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 GDPR", "scg-team-" + nonce);
        ownerId = ScheduleCommentTestFixtures.insertUser(em, "scg-owner-" + nonce + "@example.com", "本人");
        em.flush();

        scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("F0316 GDPR 検証予定")
                .startAt(LocalDateTime.of(2026, 10, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 10, 1, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(ownerId)
                .build()).getId();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-35 @PersonalData の category 文字列と PersonalDataCollector の登録キーが完全に一致する")
    void AC35_注釈のカテゴリと収集キーが一致する() {
        String annotated = ScheduleCommentEntity.class.getAnnotation(PersonalData.class).category();

        assertThat(annotated)
                .as("注釈の category が変わると収集キーとの突合が静かに壊れる")
                .isEqualTo(CATEGORY);
        assertThat(personalDataCollector.getCategoryKeys())
                .as("Validator は不足を ERROR ログに書くだけで起動を止めない。"
                        + "chat 側（注釈 chatMessages / キー chat_messages）と同じ食い違いを繰り返さないこと")
                .contains(CATEGORY);
    }

    @Test
    @DisplayName("AC-35 収集処理が自分の投稿コメント（削除済みを含む）を実際に JSON で返す（\"[]\" を返すスタブでは満たさない）")
    void AC35_収集処理が実データを返す() throws Exception {
        UUID aliveId = saveComment("生存しているコメント", false);
        UUID deletedId = saveComment("削除済みのコメント", true);
        // 他人のコメントは自分のエクスポートに混ざってはならない。
        Long otherUserId = ScheduleCommentTestFixtures.insertUser(
                em, "scg-other-" + System.nanoTime() + "@example.com", "他人");
        em.flush();
        scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(otherUserId)
                .body("他人のコメント")
                .depth(0)
                .build());
        em.flush();
        em.clear();

        Map<String, String> collected = personalDataCollector.collect(ownerId, Set.of(CATEGORY));

        assertThat(collected)
                .as("要求したカテゴリが収集結果に現れないと、エクスポートから丸ごと欠落する")
                .containsKey(CATEGORY);

        JsonNode json = objectMapper.readTree(collected.get(CATEGORY));
        assertThat(json.isArray()).isTrue();
        assertThat(json).as("\"[]\" を返すスタブでは AC を満たさない").isNotEmpty();

        assertThat(json.toString())
                .contains(aliveId.toString())
                .as("削除済みも本人のデータなのでエクスポートに含める")
                .contains(deletedId.toString())
                .as("他人のコメントが混入してはならない")
                .doesNotContain("他人のコメント");

        JsonNode first = json.get(0);
        assertThat(first.has("id")).isTrue();
        assertThat(first.has("scheduleId")).isTrue();
        assertThat(first.has("body")).isTrue();
        assertThat(first.has("createdAt")).isTrue();
        assertThat(first.has("updatedAt")).isTrue();
        assertThat(first.has("isEdited")).isTrue();
    }

    private UUID saveComment(String body, boolean deleted) {
        ScheduleCommentEntity entity = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(ownerId)
                .body(body)
                .depth(0)
                .build());
        if (deleted) {
            entity.softDelete();
            scheduleCommentRepository.save(entity);
        }
        em.flush();
        em.clear();
        return entity.getId();
    }
}
