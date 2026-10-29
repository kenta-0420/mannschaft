package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedInService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261011-0035 ALIC-1 AC-F05（試練 red）: read-batch API の宣言と api_gate 台帳。
 *
 * <ul>
 *   <li>{@code POST /api/v1/organizations/{orgId}/announcements/read-batch} を受ける Controller メソッドが
 *       ちょうど1つあり、{@link AuthorizedInService}（認可は判定部品で行う）と
 *       {@code @AlwaysReachable(category = CORE)} を宣言している。</li>
 *   <li>{@code api_gate_declaration_freeze.txt} で、その Controller の未宣言数が増えていない
 *       （新設クラスなら {@code 0|N}、既存 {@code AnnouncementFeedOrgController} に足すなら {@code 6|7}）。
 *       台帳全体の差分が「read-batch の1行（または1クラス行の総数+1）だけ」であることは検分で git diff を見る。</li>
 *   <li>read-batch を実 HTTP で叩く契約 IT（{@code OrgAnnouncementReadBatchIT} 想定）が存在し、パスを名指ししている。</li>
 * </ul>
 */
@DisplayName("ALIC-1 F05 read-batch の API 宣言と api_gate 台帳")
class AnnouncementReadBatchApiGateDeclarationTest {

    private static final String READ_BATCH = "/api/v1/organizations/{}/announcements/read-batch";
    private static final Path FREEZE = Paths.get("src/test/resources/api_gate/api_gate_declaration_freeze.txt");
    private static final Path TEST_ROOT = Paths.get("src/test/java/com/mannschaft/app/social/announcement");

    /** main 時点の既存クラスの未宣言数（read-batch を既存クラスへ足す場合も、ここから増やさない）。 */
    private static final Map<String, Integer> BASELINE_UNDECLARED = Map.of(
            "com.mannschaft.app.social.announcement.controller.AnnouncementFeedOrgController", 6,
            "com.mannschaft.app.social.announcement.controller.AnnouncementFeedController", 6);

    @Test
    @DisplayName("AC-ALIC1-F05 read-batch の Controller メソッドが1つだけあり @AuthorizedInService・@AlwaysReachable(CORE) を持つ")
    void readBatchの宣言() throws Exception {
        List<Method> methods = readBatchMethods();
        assertThat(methods).as("POST %s を受けるメソッド", READ_BATCH).hasSize(1);
        Method method = methods.get(0);
        assertThat(AnnotatedElementUtils.hasAnnotation(method, AuthorizedInService.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), AuthorizedInService.class))
                .as("@AuthorizedInService（認可は AnnouncementReadCommandService が行う）").isTrue();
        AlwaysReachable reachable = method.getAnnotation(AlwaysReachable.class);
        assertThat(reachable).as("@AlwaysReachable").isNotNull();
        assertThat(reachable.category()).isEqualTo(AlwaysReachableCategory.CORE);
        assertThat(reachable.reason()).isNotBlank();
    }

    @Test
    @DisplayName("AC-ALIC1-F05 api_gate 台帳で read-batch の Controller の未宣言数が増えていない")
    void 台帳の未宣言数() throws Exception {
        List<Method> methods = readBatchMethods();
        assertThat(methods).as("前提: read-batch の Controller メソッドがある").hasSize(1);
        String fqcn = methods.get(0).getDeclaringClass().getName();
        List<String> lines;
        try (Stream<String> stream = Files.lines(FREEZE, StandardCharsets.UTF_8)) {
            lines = stream.filter(line -> line.startsWith("HTTP|" + fqcn + "|")).toList();
        }
        assertThat(lines).as("台帳の %s の行", fqcn).hasSize(1);
        String[] cols = lines.get(0).split("\\|");
        int undeclared = Integer.parseInt(cols[2]);
        assertThat(undeclared).as("未宣言数は増やさない").isEqualTo(BASELINE_UNDECLARED.getOrDefault(fqcn, 0));
    }

    @Test
    @DisplayName("AC-ALIC1-F05 read-batch を実 HTTP で叩く契約 IT が存在する（ScopeContract 系から名指し）")
    void 契約ITが名指しする() throws IOException {
        List<Path> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith("IT.java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("/announcements/read-batch")) {
                    hits.add(file);
                }
            }
        }
        assertThat(hits).as("read-batch を名指しする IT").isNotEmpty();
    }

    private static List<Method> readBatchMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Method> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.mannschaft.app.social.announcement")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            List<String> bases = paths(AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class));
            for (Method method : type.getDeclaredMethods()) {
                PostMapping post = AnnotatedElementUtils.findMergedAnnotation(method, PostMapping.class);
                if (post == null) {
                    continue;
                }
                List<String> subs = post.value().length > 0 ? List.of(post.value())
                        : post.path().length > 0 ? List.of(post.path()) : List.of("");
                for (String base : bases) {
                    for (String sub : subs) {
                        if (normalize(base + sub).equals(READ_BATCH)) {
                            found.add(method);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static List<String> paths(RequestMapping mapping) {
        if (mapping == null) {
            return List.of("");
        }
        String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return values.length > 0 ? List.of(values) : List.of("");
    }

    private static String normalize(String path) {
        return path.replaceAll("\\{[^}]*}", "{}").replaceAll("/+", "/");
    }
}
