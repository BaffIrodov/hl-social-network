package ru.otus.socialnetwork.domain;

import java.time.LocalDate;

public record UserProfile(int id, String firstName, String secondName, LocalDate birthdate,
                          Gender gender, String biography, String city) {
}
