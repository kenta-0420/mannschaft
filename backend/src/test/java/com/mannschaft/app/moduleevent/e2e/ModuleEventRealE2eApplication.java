package com.mannschaft.app.moduleevent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.MannschaftApplication;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * M21専用の実App host。通常main＋明示Fixturesだけを起動し、同hostの検体gateを制御する。
 * 実行はroot別承認後。production CPと本2source由来JAR以外のproject testclassesを載せない。
 * 新HTTP/製品設定は追加しない。監査保持/C・F業務接続の証明には使わない。
 */
public final class ModuleEventRealE2eApplication {
    private ModuleEventRealE2eApplication() { }
    public static void main(String[] args) throws Exception {
        Path parent = Path.of(required("M21_RUN_DIR")).toAbsolutePath().normalize();
        Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        ModuleEventRealE2eFixtures.require(parent.startsWith(temporary) && Files.isDirectory(parent)
                && parent.toRealPath().startsWith(temporary), "自己Temp以外では開始しない");
        Path run = Files.createDirectory(parent.resolve("m21-" + UUID.randomUUID()));
        Path controls = Files.createDirectory(run.resolve("controls"));
        Path frontend = Path.of(required("M21_FRONTEND_DIR")).toRealPath();
        Path cli = frontend.resolve("node_modules/@playwright/test/cli.js");
        ModuleEventRealE2eFixtures.require(Files.isRegularFile(cli), "既存Playwright依存が必要。自動installしない");
        ModuleEventRealE2eFixtures.require(ModuleEventRealE2eApplication.class.getClassLoader()
                .getResource("com/mannschaft/app/moduleevent/ModuleEventRegistryIT.class") == null,
                "他project testclassesをCPへ混入させない");
        // 最終統合HEADのproduction CPはrootが明示する。限定compile用の旧CPとは区別する。
        String productionCp = required("M21_PRODUCTION_CP");
        Path fixtureJar = Path.of(required("M21_FIXTURE_JAR")).toRealPath();
        ModuleEventRealE2eFixtures.require(Files.isRegularFile(fixtureJar)
                && System.getProperty("java.class.path").equals(productionCp + java.io.File.pathSeparator + fixtureJar),
                "最終production CPと専用fixture JARを明示した起動だけを受け付ける");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        SpringApplication application = new SpringApplication(MannschaftApplication.class, ModuleEventRealE2eFixtures.class);
        // default configurationの推測ではなく、上の2 sourceだけを明示登録する。
        application.addInitializers(context -> validate(context.getEnvironment()));
        ConfigurableApplicationContext context = null;
        Process child = null;
        Process residentHelper = null;
        Instant helperStarted = null;
        ModuleEventRealE2eFixtures.World world = null;
        boolean formalPassed = false;
        boolean explorationCompleted = false;
        boolean passed = false;
        Exception problem = null;
        try {
            context = application.run(args);
            ModuleEventRealE2eFixtures.Domain domain = context.getBean(ModuleEventRealE2eFixtures.Domain.class);
            world = context.getBean(ModuleEventRealE2eFixtures.World.class);
            domain.seed();
            write(json, run.resolve("state.json"), domain.snapshot());
            ProcessBuilder builder = new ProcessBuilder(required("M21_NODE_EXE"), cli.toString(), "test",
                    "tests/e2e/real/l1m-module-events.real.spec.ts", "--config=playwright-real.config.ts",
                    "--project=chromium-real", "--no-deps", "--workers=1", "--retries=0", "--reporter=list",
                    "--trace=on", "--output=" + run.resolve("playwright-results"));
            builder.directory(frontend.toFile());
            // 資格情報はprivate子process環境だけ。ファイル・コマンド引数・stdoutへ保存しない。
            builder.environment().put("M21_ACTORS_JSON", json.writeValueAsString(world.credentials()));
            builder.environment().put("M21_RUN_DIRECTORY", run.toString());
            builder.environment().put("BASE_URL", "http://localhost:3001");
            builder.environment().put("API_BASE_URL", "http://localhost:8081");
            builder.redirectErrorStream(true).redirectOutput(run.resolve("playwright.raw.txt").toFile());
            try (WatchService watcher = controls.getFileSystem().newWatchService()) {
                controls.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                child = builder.start();
                Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
                Set<String> accepted = new java.util.HashSet<>();
                while (child.isAlive()) {
                    ModuleEventRealE2eFixtures.require(Instant.now().isBefore(deadline), "M21子process待機上限");
                    WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
                    if (key != null) {
                        for (var event : key.pollEvents()) {
                            ModuleEventRealE2eFixtures.require(event.kind() != StandardWatchEventKinds.OVERFLOW, "制御eventの欠損");
                            String command = event.context().toString();
                            ModuleEventRealE2eFixtures.require(accepted.add(command), "制御commandの重複");
                            switch (command) {
                                case "release-delayed" -> { world.release("delayed"); world.release("organization"); }
                                case "revoke-and-seed-quarantine" -> domain.revokeAndSeedQuarantine();
                                case "release-last" -> world.release("quarantine");
                                default -> throw new IllegalStateException("未知のM21制御command");
                            }
                            Files.delete(controls.resolve(command));
                        }
                        ModuleEventRealE2eFixtures.require(key.reset(), "制御ディレクトリが失われた");
                    }
                    write(json, run.resolve("state.json"), domain.snapshot());
                }
                ModuleEventRealE2eFixtures.require(child.waitFor() == 0, "実機spec失敗。private rawを確認");
                JsonNode result = json.readTree(run.resolve("ui-result.json").toFile());
                ModuleEventRealE2eFixtures.require(result.path("passed").asBoolean(false), "全step完了の証拠がない");
                ModuleEventRealE2eFixtures.require(accepted.equals(Set.of("release-delayed", "revoke-and-seed-quarantine", "release-last")),
                        "制御シナリオが未完了");
                write(json, run.resolve("final-state.json"), domain.snapshot());
                formalPassed = true;
                residentHelper = startResidentHelper(json, run, temporary, world);
                helperStarted = residentHelper.info().startInstant().orElseThrow();
                write(json, run.resolve("resident-helper-owner.json"), Map.of("pid", residentHelper.pid(),
                        "creation", helperStarted.toString(), "runId", run.getFileName().toString(),
                        "executable", residentHelper.info().command().orElseThrow()));
                awaitExploration(json, run, residentHelper);
                explorationCompleted = true;
                passed = true;
            }
        } catch (Exception failure) {
            problem = failure;
        } finally {
            // 住民REPL/browserの停止はrootが完了markerを書く前に確認する。まず自己IPCを回収する。
            try {
                stopResidentHelper(residentHelper, helperStarted);
            } catch (Exception cleanup) {
                passed = false;
                if (problem == null) problem = cleanup; else problem.addSuppressed(cleanup);
            }
            if (world != null) world.releaseAll();
            if (child != null && child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroy);
                child.destroy();
                if (!child.waitFor(10, TimeUnit.SECONDS)) {
                    child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly();
                    ModuleEventRealE2eFixtures.require(child.waitFor(10, TimeUnit.SECONDS), "自己子processが残留");
                }
            }
            if (context != null) {
                try {
                    // 自分が作成したApp contextだけ。停止後にDB生成IDを限定して掃除する。
                    stopWorkers(context);
                    if (world != null && !world.users.isEmpty()) {
                        context.getBean(ModuleEventRealE2eFixtures.Domain.class).cleanup();
                    }
                } catch (Exception cleanup) {
                    passed = false;
                    if (problem == null) problem = cleanup; else problem.addSuppressed(cleanup);
                } finally { context.close(); }
            }
            write(json, run.resolve("host-result.json"), Map.of("passed", passed && problem == null,
                    "formalPassed", formalPassed, "explorationCompleted", explorationCompleted,
                    "contextClosed", context == null || !context.isActive(), "execution", "M21検体・実業務採用証明ではない",
                    "resourceDisposal", "root所有wrapperが専用DB/Valkey/container/volumeを所有照合して撤去する"));
        }
        if (problem != null) throw problem;
        ModuleEventRealE2eFixtures.require(passed, "M21未合格");
    }

    private static Process startResidentHelper(ObjectMapper json, Path run, Path temporary,
            ModuleEventRealE2eFixtures.World world) throws Exception {
        Path helper = Path.of(required("M21_RESIDENT_HELPER")).toRealPath();
        String expected = required("M21_RESIDENT_HELPER_SHA256");
        ModuleEventRealE2eFixtures.require(helper.startsWith(temporary) && Files.isRegularFile(helper)
                && helper.getFileName().toString().endsWith(".cjs") && Files.size(helper) <= 65536
                && expected.matches("[a-fA-F0-9]{64}"), "自己Tempの固定helperとSHAが必要");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(helper)));
        ModuleEventRealE2eFixtures.require(actual.equalsIgnoreCase(expected), "住民helperのSHA不一致");
        ProcessBuilder builder = new ProcessBuilder(required("M21_NODE_EXE"), helper.toString());
        builder.directory(run.toFile());
        builder.environment().clear();
        // Nodeは絶対path、helperは標準moduleのみなのでPATHも親から継承しない。
        for (String key : List.of("SystemRoot", "WINDIR", "TEMP", "TMP")) {
            String value = System.getenv(key);
            if (value != null) builder.environment().put(key, value);
        }
        // 同Windows信頼済み自己run・生成テスト資格情報だけ。ランダムpipe名を認可とは扱わない。
        builder.environment().put("M21_RUN_DIRECTORY", run.toString());
        builder.environment().put("M21_ACTORS_JSON", json.writeValueAsString(world.credentials()));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static void awaitExploration(ObjectMapper json, Path run, Process helper) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
        Path marker = run.resolve("controls/exploration-finished.json");
        Path ready = run.resolve("exploration-ready.json");
        try (WatchService watcher = run.getFileSystem().newWatchService()) {
            marker.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                ModuleEventRealE2eFixtures.require(helper.isAlive(), "住民helperが早期終了した");
                ModuleEventRealE2eFixtures.require(Instant.now().isBefore(deadline), "住民探索待機上限");
                if (Files.exists(marker)) {
                    JsonNode completion = readExplorationJson(json, marker);
                    requireRun(completion, run);
                    ModuleEventRealE2eFixtures.require(completion.path("passed").isBoolean()
                            && completion.path("passed").booleanValue(), "住民探索が未完了");
                    requireResidentCodes(completion.path("actorCodes"));
                    JsonNode metadata = readExplorationJson(json, ready);
                    requireRun(metadata, run);
                    requireResidentCodes(metadata.path("actorCodes"));
                    ModuleEventRealE2eFixtures.require(metadata.path("pid").asLong(-1) == helper.pid()
                            && !metadata.path("creation").asText().isBlank()
                            && metadata.path("pipe").asText().startsWith("\\\\.\\pipe\\m21-"), "住民helper準備証拠不正");
                    JsonNode state = readExplorationJson(json, run.resolve("exploration-helper-state.json"));
                    requireRun(state, run);
                    requireResidentCodes(state.path("servedCodes"));
                    ModuleEventRealE2eFixtures.require(state.path("pid").asLong(-1) == helper.pid()
                            && state.path("failed").isBoolean() && !state.path("failed").booleanValue()
                            && helper.isAlive(), "資格情報の受渡しが未完了");
                    // markerは自律探索の証明ではない。rootが3住民の観測と終了を裏取りしてから作る。
                    return;
                }
                WatchKey key = watcher.poll(1, TimeUnit.SECONDS);
                if (key != null) {
                    ModuleEventRealE2eFixtures.require(key.pollEvents().stream()
                            .noneMatch(event -> event.kind() == StandardWatchEventKinds.OVERFLOW), "探索eventの欠損");
                    ModuleEventRealE2eFixtures.require(key.reset(), "探索制御ディレクトリが失われた");
                }
            }
        }
    }

    private static JsonNode readExplorationJson(ObjectMapper json, Path path) throws Exception {
        ModuleEventRealE2eFixtures.require(Files.isRegularFile(path) && Files.size(path) <= 16384,
                "探索証拠がないか上限超過");
        return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(Files.readAllBytes(path));
    }

    private static void requireRun(JsonNode value, Path run) {
        ModuleEventRealE2eFixtures.require(value != null && value.isObject()
                && value.path("runId").asText().equals(run.getFileName().toString()), "探索run不一致");
    }

    private static void requireResidentCodes(JsonNode value) {
        ModuleEventRealE2eFixtures.require(value.isArray() && value.size() == 3, "住民は固定3件が必要");
        Set<String> codes = new java.util.HashSet<>();
        value.forEach(code -> ModuleEventRealE2eFixtures.require(code.isTextual() && codes.add(code.textValue()),
                "住民code不正または重複"));
        ModuleEventRealE2eFixtures.require(codes.equals(Set.of("system", "admin-de", "member")), "住民code不一致");
    }

    private static void stopResidentHelper(Process helper, Instant started) throws Exception {
        if (helper == null) return;
        ModuleEventRealE2eFixtures.require(helper.isAlive(), "自己helperが停止指示前に終了した");
        boolean ownerVerified = started != null && helper.info().startInstant().filter(started::equals).isPresent();
        // PID検索ではなく、このhost自身がstartしたProcessだけを回収する。metadata取得失敗でも残留させない。
        List<ProcessHandle> descendants = helper.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        helper.destroy();
        if (!helper.waitFor(10, TimeUnit.SECONDS)) helper.destroyForcibly();
        ModuleEventRealE2eFixtures.require(helper.waitFor(10, TimeUnit.SECONDS), "自己helper残留");
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) descendant.destroyForcibly();
            descendant.onExit().get(10, TimeUnit.SECONDS);
        }
        ModuleEventRealE2eFixtures.require(ownerVerified, "自己helper所有metadata照合失敗");
    }

    private static void validate(Environment environment) {
        ModuleEventRealE2eFixtures.require(List.of(environment.getActiveProfiles()).equals(List.of("local")),
                "local通常構成だけを使う。test/e2e/openapi-genを配送証明へ流用しない");
        ModuleEventRealE2eFixtures.require(environment.getProperty("server.port", Integer.class, 8080) == 8081,
                "自己BE8081だけを使う");
        URI jdbc = URI.create(environment.getRequiredProperty("spring.datasource.url").substring("jdbc:".length()));
        String database = jdbc.getPath().substring(1);
        ModuleEventRealE2eFixtures.require("mysql".equals(jdbc.getScheme()) && loopback(jdbc.getHost())
                && database.matches("m21_[a-f0-9]{32}") && database.equals(required("M21_DATABASE")),
                "所有確認済み専用MySQL以外を拒否");
        ModuleEventRealE2eFixtures.require(loopback(environment.getProperty("spring.data.redis.host", "localhost"))
                && environment.getProperty("spring.data.redis.port", Integer.class, 6379) != 6379,
                "共有Valkeyを使用しない");
        ModuleEventRealE2eFixtures.require(environment.getProperty("mannschaft.email.simulate", Boolean.class, false),
                "既存LoggingEmailTransportだけを使用する");
        ModuleEventRealE2eFixtures.require(environment.getProperty("mannschaft.ses.sqs.queue-name", "").isBlank(),
                "実SQS購読を開始しない");
        for (String property : List.of("mannschaft.error-report.slack-webhook-url", "mannschaft.storage.endpoint", "mannschaft.ses.endpoint")) {
            String target = environment.getProperty(property, "");
            if (!target.isBlank()) {
                URI url = URI.create(target);
                ModuleEventRealE2eFixtures.require(loopback(url.getHost()) && "http".equals(url.getScheme())
                        && List.of(System.getenv().getOrDefault("M21_OWN_LOOPBACK_URLS", "").split(",")).contains(target),
                        "外部/所有不明の送信先を拒否");
            }
        }
    }
    private static boolean loopback(String host) { return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host); }
    private static String required(String name) {
        String value = System.getenv(name);
        ModuleEventRealE2eFixtures.require(value != null && !value.isBlank(), "M21 private環境が不足: " + name);
        return value;
    }
    private static void stopWorkers(ConfigurableApplicationContext context) throws InterruptedException {
        List<ThreadPoolTaskScheduler> schedulers = List.copyOf(context.getBeansOfType(ThreadPoolTaskScheduler.class).values());
        schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        List<ThreadPoolTaskExecutor> executors = List.copyOf(context.getBeansOfType(ThreadPoolTaskExecutor.class).values());
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
        for (ThreadPoolTaskScheduler scheduler : schedulers) {
            ModuleEventRealE2eFixtures.require(scheduler.getScheduledThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS),
                    "自己App schedulerが停止しなかった");
        }
        for (ThreadPoolTaskExecutor executor : executors) {
            ModuleEventRealE2eFixtures.require(executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS),
                    "自己App executorが停止しなかった");
        }
    }
    private static void write(ObjectMapper mapper, Path target, Object value) throws Exception {
        Path next = target.resolveSibling(target.getFileName() + ".next");
        mapper.writerWithDefaultPrettyPrinter().writeValue(next.toFile(), value);
        Files.move(next, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
