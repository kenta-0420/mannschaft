package com.mannschaft.app.gdpr;

import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 起動時GDPR網羅性チェック。
 * {@link PersonalData} アノテーションが付与されたエンティティのカテゴリキーが
 * {@link PersonalDataCollector} に全て登録されていることを検証する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalDataCoverageValidator implements ApplicationRunner {

    private final PersonalDataCollector personalDataCollector;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> registeredCategories = personalDataCollector.getCategoryKeys();

        // @PersonalData付きエンティティをクラスパスでスキャン
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PersonalData.class));

        Set<String> annotatedCategories = new HashSet<>();
        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.mannschaft.app");
        for (BeanDefinition bd : candidates) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                PersonalData annotation = clazz.getAnnotation(PersonalData.class);
                if (annotation != null) {
                    annotatedCategories.add(annotation.category());
                }
            } catch (ClassNotFoundException e) {
                log.warn("クラスロード失敗: {}", bd.getBeanClassName());
            }
        }

        Set<String> missing = new HashSet<>(annotatedCategories);
        missing.removeAll(registeredCategories);
        if (!missing.isEmpty()) {
            log.error("【GDPR網羅性チェック失敗】PersonalDataCollectorに未登録のカテゴリ: {}", missing);
        } else {
            log.info("【GDPR網羅性チェック】全カテゴリが登録済みです: {}件", annotatedCategories.size());
        }
    }
}
