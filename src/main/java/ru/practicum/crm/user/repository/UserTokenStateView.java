package ru.practicum.crm.user.repository;

/** Проекция состояния доступа, независимо от ранее загруженных JPA-сущностей. */
public interface UserTokenStateView {
    long getVersion();

    boolean getActive();
}
