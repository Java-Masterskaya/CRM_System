package ru.practicum.crm.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "ru.practicum.crm")
public class ArchitectureRulesTest {

    private static String getBasePackage(String postfix) {
        return "..ru.practicum.crm%s".formatted(postfix);
    }

    @ArchTest
    static final ArchRule packages_should_only_expose_api_and_events =
            SlicesRuleDefinition.slices().matching(getBasePackage(".(*).."))
                    .should().notDependOnEachOther()
                    .ignoreDependency(DescribedPredicate.alwaysTrue(),
                            resideInAnyPackage("..common..", "..base.."))
                    .ignoreDependency(DescribedPredicate.alwaysTrue(),
                            resideInAnyPackage("..api..", "..event.."))
                    .because("Пакет предоставляет наружу только свой публичный сервис-интерфейс и"
                            + " DTO. Сущности, репозитории и внутренние классы наружу не выходят.");

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .allowEmptyShould(true)
            .because("Контроллеры не должны обращатся к репозиториям напрямую.");

    @ArchTest
    static final ArchRule common_must_not_depend_on_any_other_crm_packages = noClasses()
            .that().resideInAPackage(getBasePackage(".common.."))
            .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAPackage(getBasePackage(".."))
                            .and(JavaClass.Predicates.resideOutsideOfPackage(
                                    getBasePackage(".common..")))
            )
            .because("Модуль common не должен зависить ни от одного функционального пакета.");

    @ArchTest
    static final ArchRule no_cyclic_dependencies_between_packages = SlicesRuleDefinition.slices()
            .matching(getBasePackage(".(*).."))
            .should().beFreeOfCycles()
            .because("Между функциональными пакетами не должно быть циклических зависимостей.");

    @ArchTest
    static final ArchRule event_should_not_depend_on_heavy_layers = noClasses()
            .that().resideInAPackage("..event..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..service..", "..repository..")
            .allowEmptyShould(true)
            .because("события (events) должны быть изолированными и легковесными. "
                    + "Они не могут знать о существовании бизнес-логики, "
                    + "контроллеров или баз данных.");

    @ArchTest
    static final ArchRule test_methods_must_follow_naming_convention = classes()
            .should(new ArchCondition<>("описывающие сценарий и результат") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (method.isAnnotatedWith(Test.class)) {
                            String methodName = method.getName();

                            if ("contextLoads".equals(methodName)
                                    || methodName.startsWith("should")
                                    || methodName.startsWith("context")) {
                                continue;
                            }

                            if (!methodName.contains("_")) {
                                String message =
                                        String.format("Тест %s.%s() должен содержать '_'",
                                                javaClass.getSimpleName(),
                                                methodName);
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    }
                }
            })
            .because("мы зафиксировали соглашение: имена тестовых методов "
                    + "должны строиться по паттерну 'given_when_then'.");
}
