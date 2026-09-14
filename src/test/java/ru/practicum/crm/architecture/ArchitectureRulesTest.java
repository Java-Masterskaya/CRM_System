package ru.practicum.crm.architecture;

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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "ru.practicum.crm")
public class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule packages_should_only_expose_api_and_events = noClasses()
            .that().resideInAPackage("crm.(*)..")
            .should().dependOnClassesThat()
            .haveNameMatching("crm\\.(?!\\1\\b)[^.]+\\.(domain|repository|service\\.impl)\\..*")
            .allowEmptyShould(true)
            .because("Пакет предоставляет наружу только свой публичный сервис-интерфейс и DTO. Сущности, репозитории и внутренние классы наружу не выходят.");

    @ArchTest
    static final ArchRule controllers_must_not_access_repositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .allowEmptyShould(true)
            .because("Контроллеры не должны обращатся к репозиториям напрямую.");

    @ArchTest
    static final ArchRule common_must_not_depend_on_any_other_crm_packages = noClasses()
            .that().resideInAPackage("crm.common..")
            .should().dependOnClassesThat().resideInAPackage("crm..")
            .andShould().dependOnClassesThat().resideOutsideOfPackage("crm.common..")
            .allowEmptyShould(true)
            .because("Модуль common не должен зависить ни от одного функционального пакета.");

    @ArchTest
    static final ArchRule no_cyclic_dependencies_between_packages = SlicesRuleDefinition.slices()
            .matching("crm.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("Между функциональными пакетами не должно быть циклических зависимостей.");

    @ArchTest
    static final ArchRule event_should_not_depend_on_heavy_layers = noClasses()
            .that().resideInAPackage("..event..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..service..", "..repository..")
            .allowEmptyShould(true)
            .because("события (events) должны быть изолированными и легковесными. " +
                    "Они не могут знать о существовании бизнес-логики, контроллеров или баз данных.");

    @ArchTest
    static final ArchRule test_methods_must_follow_naming_convention = classes()
            .should(new ArchCondition<>("содержать только методы тестирования, описывающие сценарий и результат") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (method.isAnnotatedWith(Test.class)) {
                            String methodName = method.getName();

                            if ("contextLoads".equals(methodName)) continue;

                            if (!methodName.contains("_")) {
                                String message = String.format(
                                        "Тест %s.%s() оформлен не по конвенции! " +
                                                "Имя должно описывать БИЗНЕС-СЦЕНАРИЙ и ОЖИДАЕМЫЙ РЕЗУЛЬТАТ через '_', а не имя метода.",
                                        javaClass.getSimpleName(), methodName);
                                events.add(SimpleConditionEvent.violated(method, message));
                            }
                        }
                    }
                }
            })
            .allowEmptyShould(true)
            .because("Имена тестовых методов должны строиться по паттерну 'given_when_then' " +
                    "или 'сценарий_ожидаемыйРезультат' через нижнее подчеркивание для генерации понятных отчетов.");
}
