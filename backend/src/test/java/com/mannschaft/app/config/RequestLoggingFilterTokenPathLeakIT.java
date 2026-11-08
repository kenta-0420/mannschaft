package com.mannschaft.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 試練（CMP-260826-1457 W0・AC-10b）: token を含むパスを実 HTTP で叩いても、{@link RequestLoggingFilter} の
 * {@code request_completed} ログに平文が残らない。
 *
 * <p><b>なぜ RANDOM_PORT か</b>: AC-10b は「MockMvc ではなく実フィルタ連鎖を通す」ことを求める。ログに積まれるパスは、
 * Spring MVC がハンドラを解決したかどうか（ルート テンプレートの有無）、Security フィルタと MVC の前後関係、
 * 404 のときのディスパッチに左右されるため、実際の Tomcat・実 Security フィルタ・実 DispatcherServlet を通して確かめる。
 * 基底の共有 MOCK コンテキストとは別のコンテキストになるので {@code @DirtiesContext(AFTER_CLASS)} で閉じる。</p>
 *
 * <p>どのリクエストにも実際に発行した JWT を付ける。{@link RequestLoggingFilter} は Security フィルタの後ろで動くため、
 * 未認証で弾かれるとログ自体が出ず、「平文が出ない」ことが空検証になる。認証を付けて MVC まで届かせ、
 * {@code request_completed} が1件以上出たうえで平文が無いことを確かめる。</p>
 *
 * <p>red の理由: 現行の {@link RequestLoggingFilter} は MDC {@code path} に {@code request.getRequestURI()}（生の URI）を積むため、
 * どのケースでも平文の token がログに残る。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練 CMP-260826-1457 AC-10b: token を含むパスの平文が request_completed ログに残らない（実 HTTP）")
class RequestLoggingFilterTokenPathLeakIT extends AbstractMySqlIntegrationTest {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String TOKEN_PLACEHOLDER = "{t}";

    private static final String JSON_EMPTY_OBJECT = "{}";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration LOG_WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private RoleClaimResolver roleClaimResolver;

    @Autowired
    private StatusClaimResolver statusClaimResolver;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private Logger filterLogger;

    private Level originalLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        originalLevel = filterLogger.getLevel();
        // test プロファイルはルートを WARN に固定しているため、INFO の request_completed を捕捉できるよう下げる
        filterLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(originalLevel);
    }

    /** 陣立て書 AC-10b に列挙された token を含むパス（実在のマッピング）と、ハンドラが無い 404 のパス。 */
    static Stream<Arguments> tokenPaths() {
        return Stream.of(
                Arguments.of("GET", "/api/v1/invite/{t}"),
                Arguments.of("POST", "/api/v1/invite/{t}/join"),
                Arguments.of("POST", "/api/v1/shared-links/{t}/access"),
                Arguments.of("POST", "/api/v1/public/file-links/{t}/access"),
                Arguments.of("GET", "/api/v1/contact-invite/{t}"),
                Arguments.of("GET", "/api/v1/care-links/invitations/{t}"),
                Arguments.of("GET", "/ical/{t}.ics"),
                Arguments.of("POST", "/incoming/{t}"),
                Arguments.of("POST", "/api/v1/public/confirm/{t}"),
                // ハンドラが解決されない 404（ルート テンプレートが無いときのフォールバック経路）
                Arguments.of("GET", "/api/v1/cmp1457-no-such-route/{t}"),
                Arguments.of("GET", "/api/v1/invite/{t}/cmp1457-no-such-child"));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("tokenPaths")
    void tokenを含むパスの平文がrequest_completedログに残らない(String method, String pathTemplate) throws Exception {
        String plaintext = "cmp1457probe" + UUID.randomUUID().toString().replace("-", "");
        String requestId = "cmp1457-" + UUID.randomUUID();
        Long userId = newUser();
        String bearer = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));

        HttpRequest.BodyPublisher body = "POST".equals(method)
                ? HttpRequest.BodyPublishers.ofString(JSON_EMPTY_OBJECT)
                : HttpRequest.BodyPublishers.noBody();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + pathTemplate.replace(TOKEN_PLACEHOLDER, plaintext)))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .header(REQUEST_ID_HEADER, requestId)
                .method(method, body)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("サーバーが応答を返したこと（状態コードは問わない）").isPositive();

        await().atMost(LOG_WAIT).untilAsserted(() -> assertThat(eventsOf(requestId))
                .as("RequestLoggingFilter が %s %s の request_completed を出していること（空検証の防止）", method, pathTemplate)
                .anyMatch(event -> "request_completed".equals(event.getMessage())));

        for (ILoggingEvent event : eventsOf(requestId)) {
            assertThat(event.getFormattedMessage())
                    .as("ログ本文に平文 token が含まれない")
                    .doesNotContain(plaintext);
            assertThat(event.getMDCPropertyMap().values())
                    .as("MDC（path ほか）に平文 token が含まれない。path はルート テンプレート、"
                            + "テンプレートが無いときはセグメントを固定記号に置き換えた値にする（陣立て書 §2-2 の 7.）。実際の MDC: %s",
                            event.getMDCPropertyMap())
                    .noneMatch(value -> value != null && value.contains(plaintext));
        }
    }

    private List<ILoggingEvent> eventsOf(String requestId) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                    .toList();
        }
    }

    private Long newUser() {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        new TransactionTemplate(txManager).executeWithoutResult(status -> MembershipTestHelper.insertActiveUser(em, id));
        return id;
    }
}
