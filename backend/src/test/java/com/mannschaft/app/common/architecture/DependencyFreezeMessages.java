package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.Dependency;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** D-1/D-3/D-5の依存ごとの記録を、一意かつ既定fuzzy照合で安定する文言にする。 */
final class DependencyFreezeMessages {
    // ArchUnit 1.3.0の既定matcherは行番号の数字と閉じ括弧、および$に続く数字を無視する。
    // 同じ照合群で通番を振り、別行の同callを同一行に整形しても新規違反にしない。
    private static final Pattern IGNORED_BY_FUZZY =
        Pattern.compile("(?<=:)\\p{javaDigit}+\\)|(?<=\\$)\\p{javaDigit}+");

    private final Map<String, Integer> occurrences = new HashMap<>();

    static List<Dependency> sorted(Collection<Dependency> dependencies) {
        return dependencies.stream().sorted(Comparator.comparing(Dependency::getDescription)).toList();
    }

    String format(String prefix, Dependency dependency) {
        String description = dependency.getDescription();
        String key = IGNORED_BY_FUZZY.matcher(description).replaceAll("");
        int occurrence = occurrences.merge(key, 1, Integer::sum);
        return prefix + " | dependency: " + description + " [occurrence=" + occurrence + "]";
    }
}
