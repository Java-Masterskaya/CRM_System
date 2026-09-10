# Multi-tenant CRM System

Многоарендная CRM-система приёма и ведения заявок на обработку данных.

## Технологический стек
* Java 21
* Spring Boot 3.3.3
* Gradle (Kotlin DSL)
* PostgreSQL

## Запуск и сборка

### Сборка проекта
```bash
./gradlew build

## Запуск тестов
```bash
./gradlew test

## Запуск приложения
```bash
./gradlew bootRun


### Эндпоинты API
Базовый префикс всех эндпоинтов: /api/v1

Служебный эндпоинт проверки работоспособности:
GET http://localhost:8080/api/v1/health