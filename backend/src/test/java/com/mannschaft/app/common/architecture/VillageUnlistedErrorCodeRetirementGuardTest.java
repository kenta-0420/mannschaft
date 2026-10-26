package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 番人: 本番コードから {@code VillageErrorCode.VILLAGE_UNLISTED}（{@code VILLAGE_002}）を投げてはならない。
 *
 * <h2>なぜ禁じるのか（存在オラクルの本文経路）</h2>
 * <p>{@code VILLAGE_002} は {@code GlobalExceptionHandler} で 404 に写像済みで、
 * <b>HTTP ステータスは不在（{@code VILLAGE_001}）と一致していた</b>。しかし応答<b>本文</b>の
 * {@code error.code} が {@code VILLAGE_002} と {@code VILLAGE_001} で割れるため、
 * 攻撃者は本文だけで「その村 ID は実在するが非公開」と判別できた。ステータスを揃えただけでは
 * 秘匿は完成しない、という取りこぼしがこのコードに残っていた。</p>
 *
 * <p>根治として非可視の村はすべて<b>不在側のコードそのもの</b>（{@code VILLAGE_NOT_FOUND}）を投げる。
 * 列挙値 {@code VILLAGE_UNLISTED} は過去のクライアント互換・ステータス写像表のために残すが、
 * <b>新規に throw してはならない</b>。その約束を人の注意力ではなく本番ソースの静的走査で守る。</p>
 *
 * <p>コメント・文字列リテラルは {@link JavaSourceScanningUtils#maskCommentsAndLiterals} で潰してから
 * 走査する（Javadoc 中の説明文を違反と誤検知しないため）。</p>
 */
@DisplayName("番人: 本番コードは VILLAGE_UNLISTED を throw しない（存在オラクル遮断・AC-5）")
class VillageUnlistedErrorCodeRetirementGuardTest {

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");

    /** ステータス写像表・列挙定義そのものは対象外（throw ではないため）。 */
    private static final List<String> EXCLUDED_SUFFIXES = List.of(
            "/com/mannschaft/app/village/VillageErrorCode.java",
            "/com/mannschaft/app/common/GlobalExceptionHandler.java");

    @Test
    @DisplayName("VillageErrorCode.VILLAGE_UNLISTED の throw 箇所は 0 件")
    void noProductionThrowOfVillageUnlisted() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCE_ROOT),
                "本番ソースルートが見つかりません: " + MAIN_SOURCE_ROOT.toAbsolutePath()
                        + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(MAIN_SOURCE_ROOT)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String path = p.toString().replace(java.io.File.separatorChar, '/');
                        if (EXCLUDED_SUFFIXES.stream().anyMatch(path::endsWith)) {
                            return;
                        }
                        String content;
                        try {
                            content = Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(content);
                        String[] lines = masked.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            if (lines[i].contains("VILLAGE_UNLISTED")) {
                                violations.add(path + ":" + (i + 1));
                            }
                        }
                    });
        }

        if (!violations.isEmpty()) {
            fail("本番コードで VILLAGE_UNLISTED（VILLAGE_002）を参照している箇所がある。"
                    + "非可視の村は不在と同じ VillageErrorCode.VILLAGE_NOT_FOUND を投げること"
                    + "（本文 error.code が割れると存在オラクルになる）:\n  "
                    + String.join("\n  ", violations));
        }
    }
}
