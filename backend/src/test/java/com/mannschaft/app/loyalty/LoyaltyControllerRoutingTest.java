package com.mannschaft.app.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.RuleResponse;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * F18.1 §6 の24経路・actor/body/header束縛・形式検証を実MockMvcで固定する。
 * <p>Facadeのみmockし、認可・DB・idempotencyの業務契約は検証しない。
 * 本試験のgreenを実ScopeContract/実HTTP/実機の代替にしない。</p>
 */
class LoyaltyControllerRoutingTest {
    private static final Long ACTOR = 41L;
    private static final Long TEAM = 72L;
    private static final UUID RESOURCE = UUID.fromString("6fbe0d93-7fc9-4aef-9c18-d8dd8c609a34");
    private static final String KEY = "5e24b0b0-73e9-4efa-92fa-52a4b28da03d";
    private static final String PREFIX = "/api/v1";
    private static final String TEAM_PATH = "/teams/72/loyalty";
    private static final String RULE = "{\"name\":\"来店\",\"points\":25,\"frequency\":\"ONCE_PER_JST_DAY\",\"isSelfAwardEnabled\":true}";
    private static final String REWARD = "{\"name\":\"特典\",\"description\":null,\"requiredPoints\":20}";
    private static final String SETTINGS = "{\"expectedVersion\":null,\"isEnabled\":true,\"targetAudience\":\"ALL_AUTHENTICATED\",\"selfAwardMode\":\"STAFF_APPROVAL\"}";
    private static final String SCAN = "{\"qrToken\":\"routing-qr\",\"ruleId\":\"" + RESOURCE + "\"}";
    private static final String REDEEM_PREVIEW = "{\"qrToken\":\"routing-qr\",\"rewardId\":\"" + RESOURCE + "\"}";
    private static final String AWARD = SCAN.replace("}", ",\"expectedRuleVersion\":0,\"expectedProgramVersion\":0}");
    private static final String REDEEM = REDEEM_PREVIEW.replace("}", ",\"expectedRewardVersion\":0,\"expectedProgramVersion\":0}");
    private static final String SELF_PREVIEW = SCAN.replace("qrToken", "locationToken");
    private static final String SELF_AWARD = AWARD.replace("qrToken", "locationToken");
    private static final String ACTION = "{\"expectedRequestVersion\":0}";
    private static final String APPROVE = "{\"expectedRequestVersion\":0,\"verificationCode\":\"ABCD2345\"}";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private LoyaltyFacade facade;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        facade = mock(LoyaltyFacade.class, invocation -> {
            if (invocation.getMethod().getReturnType() == CursorPagedResponse.class) {
                // 空の途中ページもmetaとともに返す。二重dataラップを検出する。
                return CursorPagedResponse.of(List.of(), new CursorPagedResponse.CursorMeta("continue", true, 20));
            }
            if (invocation.getMethod().getReturnType() == AccountDetail.class) {
                return new AccountDetail(null, TEAM, "routing-team", 0, 0, true, true, List.of());
            }
            if (invocation.getMethod().getReturnType() == RuleResponse.class) {
                return new RuleResponse(RESOURCE, "来店", 25, Frequency.ONCE_PER_JST_DAY, true, true, 0);
            }
            if (invocation.getMethod().getReturnType() == RewardResponse.class) {
                return new RewardResponse(RESOURCE, "特典", null, 20, true, 0);
            }
            return null;
        });
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new LoyaltyController(facade))
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTOR.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                route("GET", "/loyalty/accounts/me", null, "listAccounts", 200),
                route("GET", TEAM_PATH + "/account/me", null, "account", 200),
                route("GET", TEAM_PATH + "/transactions/me", null, "transactions", 200),
                route("GET", TEAM_PATH + "/operation-catalog", null, "operationCatalog", 200),
                route("GET", TEAM_PATH + "/settings", null, "settings", 200),
                route("PUT", TEAM_PATH + "/settings", SETTINGS, "updateSettings", 200),
                route("POST", TEAM_PATH + "/rules", RULE, "createRule", 201),
                route("PUT", TEAM_PATH + "/rules/" + RESOURCE, RULE.replace("}", ",\"expectedVersion\":0,\"isActive\":true}"), "updateRule", 200),
                route("POST", TEAM_PATH + "/rewards", REWARD, "createReward", 201),
                route("PUT", TEAM_PATH + "/rewards/" + RESOURCE, REWARD.replace("}", ",\"expectedVersion\":0,\"isActive\":true}"), "updateReward", 200),
                route("POST", TEAM_PATH + "/scan-previews", SCAN, "scanPreview", 200),
                route("POST", TEAM_PATH + "/redemption-previews", REDEEM_PREVIEW, "redemptionPreview", 200),
                route("POST", TEAM_PATH + "/awards", AWARD, "award", 200),
                route("POST", TEAM_PATH + "/redemptions", REDEEM, "redeem", 200),
                route("POST", "/loyalty/self-award-bootstrap/me", "{\"locationToken\":\"routing-location\"}", "bootstrap", 200),
                route("POST", TEAM_PATH + "/self-award-previews", SELF_PREVIEW, "selfPreview", 200),
                route("POST", TEAM_PATH + "/self-awards", SELF_AWARD, "selfAward", 200),
                route("GET", "/loyalty/award-requests/me", null, "myRequests", 200),
                route("GET", "/loyalty/award-requests/" + RESOURCE + "/me", null, "myRequest", 200),
                route("POST", "/loyalty/award-requests/" + RESOURCE + "/cancel/me", ACTION, "cancel", 200),
                route("GET", TEAM_PATH + "/award-requests", null, "staffRequests", 200),
                route("GET", TEAM_PATH + "/award-requests/" + RESOURCE, null, "staffRequest", 200),
                route("POST", TEAM_PATH + "/award-requests/" + RESOURCE + "/approve", APPROVE, "approve", 200),
                route("POST", TEAM_PATH + "/award-requests/" + RESOURCE + "/reject", ACTION, "reject", 200));
    }

    private static Arguments route(String method, String path, String body, String operation, int status) {
        return Arguments.of(method, path, body, operation, status);
    }

    @ParameterizedTest(name = "{0} {1} → {3}")
    @MethodSource("routes")
    @DisplayName("24経路がSecurityUtilsのactorと型付きbodyをFacadeへ一度だけ渡す")
    void routesBindActorAndBody(String method, String path, String body, String operation, int expectedStatus) throws Exception {
        var request = request(HttpMethod.valueOf(method), PREFIX + path).header("Idempotency-Key", KEY);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        if (expectedStatus == 201) {
            assertThat(result.getResponse().getHeader("Location")).isEqualTo(PREFIX + path + "/" + RESOURCE);
        }
        var calls = mockingDetails(facade).getInvocations();
        assertThat(calls).hasSize(1);
        Invocation call = calls.iterator().next();
        assertThat(call.getMethod().getName()).isEqualTo(operation);
        assertThat(call.getArgument(0, Long.class)).isEqualTo(ACTOR);
        if (path.startsWith("/teams/")) assertThat(call.getArgument(1, Long.class)).isEqualTo(TEAM);
        if (path.contains(RESOURCE.toString())) assertThat(List.of(call.getArguments())).contains(RESOURCE);
        if (body != null) {
            Object dto = Stream.of(call.getArguments()).filter(arg -> arg != null && arg.getClass().isRecord()).findFirst().orElseThrow();
            // JSONの数値契約は同じでもLongNode/IntNodeはequalsで区別されるため、実wire表現で比較する。
            JsonNode actualBody = mapper.readTree(mapper.writeValueAsString(dto));
            assertThat(actualBody).isEqualTo(mapper.readTree(body));
        }
        if (List.of("award", "redeem", "selfAward", "cancel", "approve", "reject").contains(operation)) {
            assertThat(call.getArgument(call.getArguments().length - 1, String.class)).isEqualTo(KEY);
        }
        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        if (call.getMethod().getReturnType() == CursorPagedResponse.class) {
            assertThat(response.get("data").isArray()).isTrue();
            assertThat(response.path("meta").path("hasNext").asBoolean()).isTrue();
            assertThat(response.path("meta").path("nextCursor").asText()).isEqualTo("continue");
            assertThat(call.getArgument(call.getArguments().length - 1, Integer.class)).isEqualTo(20);
            if (operation.equals("staffRequests")) assertThat(call.getArgument(2, RequestFilter.class)).isEqualTo(RequestFilter.PENDING);
        } else {
            assertThat(response.has("data")).isTrue();
            assertThat(response.has("meta")).isFalse();
            if (operation.equals("account")) assertThat(response.path("data").path("balance").asInt(-1)).isZero();
        }
    }

    static Stream<Arguments> invalidBodies() {
        return routes().filter(args -> args.get()[2] != null)
                .map(args -> Arguments.of(args.get()[0], args.get()[1], "{}"));
    }

    @ParameterizedTest(name = "必須項目なし: {0} {1}")
    @MethodSource("invalidBodies")
    @DisplayName("全15body経路が空bodyを400にしてFacadeへ到達させない")
    void invalidBodyNeverReachesFacade(String method, String path, String body) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), PREFIX + path)
                        .header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(facade);
    }

    static Stream<Arguments> rejectedInputs() {
        Stream<Arguments> limits = routes().filter(args -> List.of("listAccounts", "transactions", "myRequests", "staffRequests").contains(args.get()[3]))
                .flatMap(args -> Stream.of(0, 101).map(limit -> Arguments.of("GET", args.get()[1] + "?limit=" + limit, null, true)));
        Stream<Arguments> missingKeys = routes().filter(args -> List.of("award", "redeem", "selfAward", "cancel", "approve", "reject").contains(args.get()[3]))
                .map(args -> Arguments.of(args.get()[0], args.get()[1], args.get()[2], false));
        Stream<Arguments> formats = Stream.of(
                Arguments.of("GET", TEAM_PATH + "/award-requests?status=UNKNOWN", null, true),
                Arguments.of("GET", "/teams/not-a-long/loyalty/account/me", null, true),
                Arguments.of("GET", "/loyalty/award-requests/not-a-uuid/me", null, true),
                Arguments.of("POST", TEAM_PATH + "/rules", RULE.replace("\"ONCE_PER_JST_DAY\"", "null"), true),
                Arguments.of("POST", TEAM_PATH + "/rules", RULE.replace("25", "0"), true),
                Arguments.of("POST", TEAM_PATH + "/rules", RULE.replace("25", "10001"), true),
                Arguments.of("POST", TEAM_PATH + "/rules", RULE.replace("25", "2147483648"), true),
                Arguments.of("POST", TEAM_PATH + "/awards", AWARD.replace("\"expectedRuleVersion\":0", "\"expectedRuleVersion\":-1"), true),
                Arguments.of("POST", TEAM_PATH + "/award-requests/" + RESOURCE + "/approve", APPROVE.replace("ABCD2345", "abcd2345"), true));
        return Stream.concat(Stream.concat(limits, missingKeys), formats);
    }

    @ParameterizedTest(name = "不正形式: {0} {1}")
    @MethodSource("rejectedInputs")
    @DisplayName("limit境界外・必須header・型・enum・数値・照合コードを400で拒否する")
    void rejectedInputNeverReachesFacade(String method, String path, String body, boolean includeKey) throws Exception {
        var request = request(HttpMethod.valueOf(method), PREFIX + path);
        if (includeKey) request.header("Idempotency-Key", KEY);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        mvc.perform(request).andExpect(status().isBadRequest());
        verifyNoInteractions(facade);
    }

    static Stream<Arguments> pageBoundaries() {
        return routes().filter(args -> List.of("listAccounts", "transactions", "myRequests", "staffRequests").contains(args.get()[3]))
                .flatMap(args -> Stream.of(1, 100).map(limit -> Arguments.of(args.get()[1], args.get()[3], limit)));
    }

    @ParameterizedTest(name = "ページ境界: {0}, limit={2}")
    @MethodSource("pageBoundaries")
    @DisplayName("4カーソル経路はlimit1と100および不透明cursorとALL filterをそのまま渡す")
    void cursorAndBoundaryAreForwarded(String path, String operation, int limit) throws Exception {
        var request = request(HttpMethod.GET, PREFIX + path).param("cursor", "opaque-cursor").param("limit", Integer.toString(limit));
        if (operation.equals("staffRequests")) request.param("status", "ALL");
        mvc.perform(request).andExpect(status().isOk());
        var calls = mockingDetails(facade).getInvocations();
        assertThat(calls).hasSize(1);
        Invocation call = calls.iterator().next();
        int length = call.getArguments().length;
        assertThat(call.getArgument(length - 2, String.class)).isEqualTo("opaque-cursor");
        assertThat(call.getArgument(length - 1, Integer.class)).isEqualTo(limit);
        if (operation.equals("staffRequests")) assertThat(call.getArgument(2, RequestFilter.class)).isEqualTo(RequestFilter.ALL);
    }
}
