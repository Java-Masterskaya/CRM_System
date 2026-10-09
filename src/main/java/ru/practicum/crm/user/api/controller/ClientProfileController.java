package ru.practicum.crm.user.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.user.api.dto.ClientProfileDto;
import ru.practicum.crm.user.api.dto.UpdateClientProfileRequest;
import ru.practicum.crm.user.service.UserService;

@RestController
@RequestMapping("/client/profile")
@RequiredArgsConstructor
public class ClientProfileController {

    private final UserService userService;

    @GetMapping
    public ClientProfileDto getProfile() {
        return userService.getClientProfile();
    }

    @PutMapping
    public ClientProfileDto updateProfile(
            @Valid @RequestBody UpdateClientProfileRequest request) {
        return userService.updateClientProfile(request);
    }
}
