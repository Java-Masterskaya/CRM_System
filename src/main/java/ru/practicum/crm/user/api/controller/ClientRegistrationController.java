package ru.practicum.crm.user.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;
import ru.practicum.crm.user.service.UserService;

@RestController
@RequestMapping("/auth/register")
@RequiredArgsConstructor
public class ClientRegistrationController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<Void> register(
            @Valid @RequestBody ClientRegistrationRequest request) {
        userService.registerClient(request);
        return ResponseEntity.status(201).build();
    }
}
