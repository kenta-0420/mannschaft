package com.mannschaft.app.moduleevent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;

/** M21の定義登録/孫環境だけを検査する。DB・full App・外部serviceは起動しない。 */
class ModuleEventRealE2eBootstrapBoundaryTest {
    private static final Set<String> FIXTURE_BEANS = Set.of("m21World", "m21Domain", "m21Resolver", "m21Listeners");
    private static final String ACTORS = "[{\"code\":\"system\",\"userId\":1,\"email\":\"fixture@example.invalid\","
            + "\"password\":\"public-test-sentinel\",\"locale\":\"ja\"}]";

    @TempDir
    Path temporary;

    @Test
    void 通常component走査ではM21検体を採用しない() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        long count = scanner.findCandidateComponents(ModuleEventRealE2eFixtures.class.getPackageName()).stream()
                .filter(definition -> ModuleEventRealE2eFixtures.class.getName().equals(definition.getBeanClassName()))
                .count();

        assertEquals(0, count, "明示source以外のcomponent走査へfixtureを混ぜない");
    }

    @Test
    void 明示sourceでは既存四beanの定義だけを登録できる() {
        var registry = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(registry).register(ModuleEventRealE2eFixtures.class);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(registry);
        Set<String> actual = Arrays.stream(registry.getBeanDefinitionNames())
                .filter(name -> name.startsWith("m21")).collect(java.util.stream.Collectors.toSet());

        assertEquals(FIXTURE_BEANS, actual);
        for (String name : FIXTURE_BEANS) {
            assertEquals(name, registry.getBeanDefinition(name).getFactoryMethodName());
        }
        // refresh/getBeanを呼ばず、DB依存を実体化しない。
        assertFalse(registry.containsSingleton("m21Domain"));
    }

    @Test
    @Timeout(20)
    void formal孫へhost専用環境を漏らさず必要値だけを渡す() throws Exception {
        Path source = temporary.resolve("M21EnvironmentChild.java");
        Files.writeString(source, CHILD_SOURCE, StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "固定JDK21で実行する");
        assertEquals(0, compiler.run(null, null, null, "--release", "21", "-proc:none", "-d",
                temporary.toString(), source.toString()));
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString(),
                "-Xmx32m", "-cp", temporary.toString(), "M21EnvironmentChild");
        builder.directory(temporary.toFile());
        // fixtureの親環境も実利用者の秘密から分離し、公開sentinelだけを注入する。
        builder.environment().clear();
        for (String name : Set.of("SystemRoot", "WINDIR")) {
            String value = System.getenv(name);
            if (value != null) builder.environment().put(name, value);
        }
        builder.environment().put("TEMP", temporary.toString());
        builder.environment().put("TMP", temporary.toString());
        for (String name : Set.of("M21_HOST_ONLY_SENTINEL", "SPRING_DATASOURCE_PASSWORD", "MANNSCHAFT_JWT_SECRET",
                "MANNSCHAFT_ENCRYPTION_KEY", "MANNSCHAFT_HMAC_KEY", "AWS_ACCESS_KEY_ID", "HTTP_PROXY", "NODE_OPTIONS")) {
            builder.environment().put(name, name.equals("NODE_OPTIONS") ? "--max-old-space-size=64" : "public-test-sentinel");
        }
        Method configure = ModuleEventRealE2eApplication.class.getDeclaredMethod("configureFormalEnvironment",
                ProcessBuilder.class, String.class, Path.class, String.class);
        configure.setAccessible(true);
        configure.invoke(null, builder, ACTORS, temporary, "http://localhost:3003");
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "固定環境子の実exit期限");
            assertEquals(0, child.exitValue());
            String stdout = new String(child.getInputStream().readNBytes(1025), StandardCharsets.UTF_8).trim();
            String stderr = new String(child.getErrorStream().readNBytes(1025), StandardCharsets.UTF_8).trim();
            assertEquals("", stderr, "固定子は秘密や例外本文を出さない");
            assertEquals("leaked=false;required=true", stdout, "実子からキー存在の結果だけを受け取る");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(2, TimeUnit.SECONDS), "保持した固定子の回収期限");
            }
            child.getOutputStream().close();
            child.getInputStream().close();
            child.getErrorStream().close();
        }
    }

    private static final String CHILD_SOURCE = """
            public final class M21EnvironmentChild {
                public static void main(String[] args) {
                    if (args.length != 0) throw new IllegalArgumentException("fixed child only");
                    String[] denied = {"M21_HOST_ONLY_SENTINEL", "SPRING_DATASOURCE_PASSWORD", "MANNSCHAFT_JWT_SECRET",
                        "MANNSCHAFT_ENCRYPTION_KEY", "MANNSCHAFT_HMAC_KEY", "AWS_ACCESS_KEY_ID", "HTTP_PROXY", "NODE_OPTIONS"};
                    boolean leaked = java.util.Arrays.stream(denied).anyMatch(key -> System.getenv(key) != null);
                    boolean required = System.getenv("M21_ACTORS_JSON") != null
                        && System.getenv("M21_ACTORS_JSON").contains("public-test-sentinel")
                        && System.getenv("M21_RUN_DIRECTORY") != null
                        && System.getenv("M21_RUN_DIRECTORY").equals(System.getenv("TEMP"))
                        && System.getenv("TEMP").equals(System.getenv("TMP"))
                        && "http://localhost:3003".equals(System.getenv("BASE_URL"))
                        && "http://localhost:8081".equals(System.getenv("API_BASE_URL"));
                    System.out.println("leaked=" + leaked + ";required=" + required);
                }
            }
            """;
}
