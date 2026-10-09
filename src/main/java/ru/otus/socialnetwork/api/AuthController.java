package ru.otus.socialnetwork.api;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.socialnetwork.api.dto.LoginRequest;
import ru.otus.socialnetwork.api.dto.LoginResponse;
import ru.otus.socialnetwork.service.AuthService;
import ru.otus.socialnetwork.validation.UserIds;

@RestController
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var response = new LoginResponse(auth.login(UserIds.parse(request.id()), request.password()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
