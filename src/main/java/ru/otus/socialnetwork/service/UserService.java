package ru.otus.socialnetwork.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.api.dto.RegisterRequest;
import ru.otus.socialnetwork.domain.Gender;
import ru.otus.socialnetwork.domain.UserProfile;
import ru.otus.socialnetwork.repository.CredentialsRepository;
import ru.otus.socialnetwork.repository.UserRepository;
import ru.otus.socialnetwork.validation.PasswordPolicy;

@Service
public class UserService {
    private final UserRepository users;
    private final CredentialsRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public UserService(UserRepository users, CredentialsRepository credentials,
                       PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy) {
        this.users = users;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional
    public int register(RegisterRequest request) {
        passwordPolicy.validate(request.password());
        String hash = passwordEncoder.encode(request.password());
        int id = users.insert(request.firstName().strip(), request.secondName().strip(), request.birthdate(),
                request.gender() == null ? Gender.UNSPECIFIED : request.gender(),
                normalizeOptional(request.biography()), normalizeOptional(request.city()));
        credentials.insert(id, hash);
        return id;
    }

    @Transactional(readOnly = true)
    public UserProfile getById(int id) {
        return users.findById(id).orElseThrow(ApiException::userNotFound);
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) { return null; }
        return value.strip();
    }
}
