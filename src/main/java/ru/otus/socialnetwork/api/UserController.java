package ru.otus.socialnetwork.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import ru.otus.socialnetwork.api.dto.RegisterRequest;
import ru.otus.socialnetwork.api.dto.RegisterResponse;
import ru.otus.socialnetwork.api.dto.UserResponse;
import ru.otus.socialnetwork.service.UserService;
import ru.otus.socialnetwork.validation.UserIds;

@RequestMapping("/user")
@RestController
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return new RegisterResponse(Integer.toString(users.register(request)));
    }

    @GetMapping("/get/{id}")
    public UserResponse get(@PathVariable String id) {
        return UserResponse.from(users.getById(UserIds.parse(id)));
    }
}