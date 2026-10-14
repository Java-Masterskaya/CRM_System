package ru.practicum.crm.user.api;

import java.util.List;

public interface PasswordPolicy {

    List<String> violations(String password);

    void validate(String password);

}
