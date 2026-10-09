package ru.otus.socialnetwork.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import ru.otus.socialnetwork.domain.Gender;
import ru.otus.socialnetwork.domain.UserProfile;

public record UserResponse(String id,
                           @JsonProperty("first_name") String firstName,
                           @JsonProperty("second_name") String secondName,
                           LocalDate birthdate, Gender gender, String biography, String city) {
    public static UserResponse from(UserProfile profile) {
        return new UserResponse(Integer.toString(profile.id()), profile.firstName(), profile.secondName(),
                profile.birthdate(), profile.gender(), profile.biography(), profile.city());
    }
}
