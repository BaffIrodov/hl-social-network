package ru.otus.socialnetwork.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.socialnetwork.api.dto.RegisterRequest;
import ru.otus.socialnetwork.api.dto.RegisterResponse;
import ru.otus.socialnetwork.api.dto.UserResponse;
import ru.otus.socialnetwork.service.UserService;
import ru.otus.socialnetwork.validation.UserIds;

@RestController
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @PostMapping(value = "/user/register", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return new RegisterResponse(Integer.toString(users.register(request)));
    }

    @GetMapping(value = "/user/get/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse get(@PathVariable String id) {
        return UserResponse.from(users.getById(UserIds.parse(id)));
    }
}
