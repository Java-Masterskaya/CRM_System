package ru.practicum.crm.attachment.domain;

/**
 * Ссылка на файл в медиа-сервисе: идентификатор объекта и то, что о файле показывают
 * пользователю.
 *
 * @param objectId идентификатор объекта в медиа-сервисе — не адрес: адрес сервиса берётся из
 *     конфигурации
 * @param fileName имя файла
 * @param sizeBytes размер файла в байтах
 * @param contentType тип содержимого, например {@code application/pdf}
 */
public record FileReference(String objectId, String fileName, long sizeBytes,
        String contentType) {
}
