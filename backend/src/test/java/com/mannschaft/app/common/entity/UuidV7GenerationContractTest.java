package com.mannschaft.app.common.entity;

import com.mannschaft.app.common.UuidV7;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** CMP-008 共通 UUIDv7 採番契約の試練。 */
@DisplayName("CMP-008 共通 UUIDv7 採番契約")
class UuidV7GenerationContractTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-17T03:04:05.678Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Test
    @DisplayName("生成値はRFC 9562 UUIDv7で48bit timestampがClockの時刻と一致する")
    void generatedUuidHasVersion7Variant2AndExpectedTimestamp() {
        UUID generated = UuidV7.generate(FIXED_CLOCK);

        assertThat(generated.version()).isEqualTo(7);
        assertThat(generated.variant()).isEqualTo(2);
        assertThat(extractUnixMillis(generated)).isEqualTo(FIXED_INSTANT.toEpochMilli());
    }

    @Test
    @DisplayName("同一ミリ秒に大量生成しても重複しない")
    void sameMillisecondGenerationIsUnique() {
        int count = 50_000;

        Set<UUID> generated = ConcurrentHashMap.newKeySet(count);
        for (int i = 0; i < count; i++) {
            generated.add(UuidV7.generate(FIXED_CLOCK));
        }

        assertThat(generated).hasSize(count);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("複数threadから同一ミリ秒に生成しても全件UUIDv7かつ重複しない")
    void concurrentSameMillisecondGenerationIsUnique() throws Exception {
        int threads = 16;
        int perThread = 2_000;
        Set<UUID> generated = ConcurrentHashMap.newKeySet(threads * perThread);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(ignored -> executor.submit(() -> {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            generated.add(UuidV7.generate(FIXED_CLOCK));
                        }
                        return null;
                    }))
                    .toList();

            start.countDown();
            for (var future : futures) {
                future.get();
            }
        }

        assertThat(generated).hasSize(threads * perThread);
        assertThat(generated).allSatisfy(uuid -> {
            assertThat(uuid.version()).isEqualTo(7);
            assertThat(uuid.variant()).isEqualTo(2);
            assertThat(extractUnixMillis(uuid)).isEqualTo(FIXED_INSTANT.toEpochMilli());
        });
    }

    @Test
    @DisplayName("共通生成器は外部I/O層に依存しない")
    void generatorDoesNotDependOnExternalIo() {
        JavaClasses imported = new ClassFileImporter().importClasses(UuidV7.class);

        noClasses()
                .should().dependOnClassesThat().resideInAnyPackage(
                        "java.io..",
                        "java.net..",
                        "java.nio.file..",
                        "java.sql..",
                        "javax.sql..",
                        "org.springframework..",
                        "jakarta.persistence..")
                .check(imported);
    }

    @Test
    @DisplayName("BINARY/CHAR双方の共通基底がアプリ共通UUIDv7生成器へ委譲する")
    void entityBasesDelegateToCommonUuidV7Generator() {
        JavaClasses imported = new ClassFileImporter().importClasses(
                UuidV7Entity.class, UuidV7CharEntity.class, UuidV7.class);

        assertThat(imported.get(UuidV7Entity.class).getDirectDependenciesFromSelf())
                .anySatisfy(dependency -> assertThat(dependency.getTargetClass().getName())
                        .isEqualTo(UuidV7.class.getName()));
        assertThat(imported.get(UuidV7CharEntity.class).getDirectDependenciesFromSelf())
                .anySatisfy(dependency -> assertThat(dependency.getTargetClass().getName())
                        .isEqualTo(UuidV7.class.getName()));
    }

    private static long extractUnixMillis(UUID uuid) {
        return (uuid.getMostSignificantBits() >>> 16) & 0xFFFF_FFFF_FFFFL;
    }
}
