package com.mannschaft.app.common.erdiagram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** er/ ディレクトリの書き出し（全件を一時 dir に作ってから置き換え）と、コミット済み内容との照合。 */
final class ErDiagramFiles {

    private ErDiagramFiles() {
    }

    /**
     * 一時ディレクトリへ全件を書き出し、成功してから {@code dir} を置き換える。
     * 書き出し中に失敗しても {@code dir} は元のまま残る（半端な状態にしない）。
     */
    static void replaceDirectory(Path dir, Map<String, String> files) {
        Path parent = dir.toAbsolutePath().getParent();
        Path tmp = parent.resolve("." + dir.getFileName() + ".tmp-" + System.nanoTime());
        Path old = parent.resolve("." + dir.getFileName() + ".old-" + System.nanoTime());
        try {
            Files.createDirectories(tmp);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Files.write(tmp.resolve(e.getKey()), e.getValue().getBytes(StandardCharsets.UTF_8));
            }
            boolean hadOld = Files.exists(dir);
            if (hadOld) {
                Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
            }
            try {
                Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                if (hadOld) {
                    Files.move(old, dir, StandardCopyOption.ATOMIC_MOVE);
                }
                throw e;
            }
            if (hadOld) {
                deleteRecursively(old);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("ER 図の書き出しに失敗した（" + dir + " は変更していない）", e);
        } catch (RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /**
     * コミット済み {@code dir} と生成結果 {@code expected} を照合し、不一致の説明を返す（空なら一致）。
     * 内容差分・欠落・余剰ファイルを検出し、メッセージには該当ファイル名と再生成コマンドを含める。
     */
    static List<String> diff(Path dir, Map<String, String> expected) {
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> actual = new TreeMap<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    if (Files.isRegularFile(p)) {
                        actual.put(p.getFileName().toString(), Files.readAllBytes(p));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (Map.Entry<String, String> e : new TreeMap<>(expected).entrySet()) {
            byte[] committed = actual.get(e.getKey());
            if (committed == null) {
                problems.add("欠落: " + e.getKey() + "（生成結果にあるがコミットされていない）");
            } else if (!java.util.Arrays.equals(committed, e.getValue().getBytes(StandardCharsets.UTF_8))) {
                problems.add("内容差分: " + e.getKey());
            }
        }
        for (String name : actual.keySet()) {
            if (!expected.containsKey(name)) {
                problems.add("余剰: " + name + "（コミットされているが生成結果にない）");
            }
        }
        return problems;
    }

    /** 照合失敗時のメッセージ（ファイル名と再生成コマンドを含む）。 */
    static String driftMessage(List<String> problems) {
        return "docs/architecture/er/ が現在のスキーマの生成結果と一致しない:\n  - " + String.join("\n  - ", problems)
                + "\n再生成して差分をコミットすること: " + ErDiagramRenderer.REGEN_COMMAND;
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(x);
            }
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            deleteRecursively(p);
        } catch (IOException ignored) {
            // 後始末の失敗は元の例外を隠さない
        }
    }
}
