package com.mannschaft.spike;

import com.mannschaft.app.MannschaftApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import static org.assertj.core.api.Assertions.assertThat;

/** 全本番の依存違反/循環と最小検体をSpringとは別JVMで測る。凍結ストアを触らない。 */
@Tag("archunit")
class StructureSpikeIT {
    @Test
    void 全本番の発生元別依存違反と循環参加を記録する() throws Exception {
        var modules = ApplicationModules.of(MannschaftApplication.class, location -> location.contains("/classes/java/main/"));
        var imported = modules.getRootPackages().stream().flatMap(root -> root.stream())
                .map(JavaClass::getName).collect(java.util.stream.Collectors.toSet());
        Set<String> compiled;
        var compiledRoot = Path.of("build/classes/java/main");
        try (var files = Files.walk(compiledRoot)) {
            compiled = files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> compiledRoot.relativize(file).toString().replace(java.io.File.separatorChar, '.').replace('/', '.'))
                    .map(name -> name.substring(0, name.length() - 6))
                    .filter(name -> !name.endsWith("package-info") && !name.endsWith("module-info"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertThat(imported).containsAll(compiled);
        assertThat(imported).noneMatch(name -> name.contains("SpikeIT") || name.startsWith("com.mannschaft.spike."));
        var all = modules.detectViolations().getMessages();
        Map<String, Integer> dependencies = new LinkedHashMap<>();
        Set<String> dependencyMessages = new LinkedHashSet<>();
        for (var module : modules) {
            var messages = module.detectDependencies(modules).getMessages();
            dependencies.put(module.getIdentifier().toString(), messages.size());
            dependencyMessages.addAll(messages);
        }
        var cycles = all.stream().filter(message -> message.startsWith("Cycle detected:")).toList();
        var unknown = all.stream().filter(message -> !message.startsWith("Cycle detected:"))
                .filter(message -> !dependencyMessages.contains(message)).toList();
        var cyclePattern = Pattern.compile("Slice ([A-Za-z0-9_]+)");
        List<String> cycleMembers = new ArrayList<>();
        int webhookCycles = 0;
        int commonCycles = 0;
        for (String cycle : cycles) {
            var matcher = cyclePattern.matcher(cycle);
            Set<String> members = new LinkedHashSet<>();
            while (matcher.find()) { members.add(matcher.group(1)); }
            assertThat(members).as("循環参加名を抽出できること").isNotEmpty();
            for (String member : members) { assertThat(modules.getModuleByName(member)).isPresent(); }
            if (members.contains("webhook")) { webhookCycles++; }
            if (members.contains("common")) { commonCycles++; }
            cycleMembers.add(String.join(",", members));
        }
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (var module : modules) {
            graph.put(module.getIdentifier().toString(), module.getDirectDependencies(modules).uniqueModules()
                    .map(target -> target.getIdentifier().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        List<Set<String>> stronglyConnected = new ArrayList<>();
        Set<String> assigned = new LinkedHashSet<>();
        for (String source : graph.keySet()) {
            if (assigned.contains(source)) { continue; }
            Set<String> component = new LinkedHashSet<>();
            for (String target : graph.keySet()) {
                if (source.equals(target) || (reaches(graph, source, target) && reaches(graph, target, source))) {
                    component.add(target);
                }
            }
            assigned.addAll(component);
            if (component.size() > 1) { stronglyConnected.add(component); }
        }
        var output = Path.of(System.getProperty("spike.output") + "-structure.txt");
        String summary = "compiledProductionClasses=" + compiled.size() + "\nimportedProductionClasses=" + imported.size() + "\nmodules=" + modules.stream().count() + "\nall=" + all.size()
                + "\ndependencyTotal=" + dependencies.values().stream().mapToInt(Integer::intValue).sum()
                + "\ncommonOriginDependencies=" + dependencies.get("common")
                + "\nwebhookOriginDependencies=" + dependencies.get("webhook")
                + "\nreportedCycles=" + cycles.size() + "\ncommonParticipatingCycles=" + commonCycles
                + "\nwebhookParticipatingCycles=" + webhookCycles + "\nunknown=" + unknown.size();
        Files.writeString(output, summary + "\n\noriginCounts=" + dependencies
                + "\n\ncyclicStrongComponents=" + stronglyConnected + "\n\ncycleParticipants=" + cycleMembers + "\n\n" + String.join("\n---\n", all));
        assertThat(unknown).as("全報告を種類別に漏れなく分類できること").isEmpty();
        assertThat(dependencies).containsKeys("common", "webhook");
    }

    @Test
    void jobmatchingからchatのapiと自身の購読だけなら循環0() {
        var modules = ApplicationModules.of("com.mannschaft.spike.pattern");
        assertThat(modules.stream().map(module -> module.getIdentifier().toString())).containsExactlyInAnyOrder("chat", "jobmatching");
        modules.verify();
    }

    @Test
    void 橋渡しだけの循環は端点除外で消える() {
        var before = ApplicationModules.of("com.mannschaft.spike.bridgeonly");
        assertThat(before.detectViolations().getMessages()).anyMatch(message -> message.startsWith("Cycle detected:"));
        ApplicationModules.of("com.mannschaft.spike.bridgeonly", bridges()).verify();
    }

    @Test
    void 橋渡し以外のcommon辺は除外後も循環として残る() {
        var modules = ApplicationModules.of("com.mannschaft.spike.bridgeremains", bridges());
        assertThat(modules.detectViolations().getMessages()).anyMatch(message -> message.startsWith("Cycle detected:"));
    }

    static boolean reaches(Map<String, Set<String>> graph, String source, String target) {
        Set<String> seen = new LinkedHashSet<>();
        var pending = new java.util.ArrayDeque<String>(graph.getOrDefault(source, Set.of()));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (current.equals(target)) { return true; }
            if (seen.add(current)) { pending.addAll(graph.getOrDefault(current, Set.of())); }
        }
        return false;
    }

    static DescribedPredicate<JavaClass> bridges() {
        return new DescribedPredicate<>("TransitionalBridgeの付いたクラスだけ") {
            @Override public boolean test(JavaClass type) { return type.isAnnotatedWith(TransitionalBridge.class); }
        };
    }
}