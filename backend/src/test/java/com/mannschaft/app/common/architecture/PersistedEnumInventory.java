package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Transient;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** STRING 永続化 enum の互換性台帳に載せる定数を列挙する。 */
final class PersistedEnumInventory {
    private PersistedEnumInventory() {
    }

    static Set<String> constants(JavaClasses classes) {
        Set<String> constants = new TreeSet<>();
        for (JavaClass type : classes) {
            for (var field : type.getFields()) {
                if (isStringMapping(field) && !field.getModifiers().contains(JavaModifier.TRANSIENT)) {
                    addConstants(field.getType(), field.getFullName(), constants);
                }
            }
            for (var method : type.getMethods()) {
                if (isStringMapping(method)) {
                    if (!method.getRawParameterTypes().isEmpty()) {
                        throw new IllegalStateException("STRING注釈のpropertyに引数があります: " + method.getFullName());
                    }
                    addConstants(method.getReturnType(), method.getFullName(), constants);
                }
            }
        }
        return constants;
    }

    private static boolean isStringMapping(JavaMember member) {
        return !member.getModifiers().contains(JavaModifier.STATIC)
                && !member.isAnnotatedWith(Transient.class)
                && member.isAnnotatedWith(Enumerated.class)
                && member.getAnnotationOfType(Enumerated.class).value() == EnumType.STRING;
    }

    /** 型解決不能・未対応型を黙って対象外にすると番人の死角になるため、明示的に失敗させる。 */
    private static void addConstants(JavaType type, String location, Set<String> constants) {
        List<JavaClass> enums = type.getAllInvolvedRawTypes().stream().filter(JavaClass::isEnum).toList();
        if (enums.size() != 1 || (!type.toErasure().isEnum()
                && !type.toErasure().isAssignableTo(Collection.class))) {
            throw new IllegalStateException("STRING注釈のenum型を解決できません: " + location + " / " + type.getName());
        }
        JavaClass enumType = enums.getFirst();
        if (!enumType.isFullyImported() || enumType.getEnumConstants().isEmpty()) {
            throw new IllegalStateException("STRING永続化enumの定数を読めません: " + location + " / " + enumType.getName());
        }
        enumType.getEnumConstants().forEach(value -> constants.add(enumType.getName() + "#" + value.name()));
    }
}
