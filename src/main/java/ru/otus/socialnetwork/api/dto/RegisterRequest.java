package ru.otus.socialnetwork.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

import ru.otus.socialnetwork.domain.Gender;

public record RegisterRequest(
        @JsonProperty("first_name")
        @NotBlank(message = "Имя обязательно и не должно состоять из пробелов")
        @Size(max = 100, message = "Имя должно содержать не более 100 символов")
        String firstName,
        @JsonProperty("second_name")
        @NotBlank(message = "Фамилия обязательна и не должна состоять из пробелов")
        @Size(max = 100, message = "Фамилия должна содержать не более 100 символов")
        String secondName,
        @NotNull(message = "Дата рождения обязательна")
        @PastOrPresent(message = "Дата рождения не может быть в будущем")
        LocalDate birthdate,
        Gender gender,
        @Size(max = 2000, message = "Интересы должны содержать не более 2000 символов")
        String biography,
        @Size(max = 100, message = "Город должен содержать не более 100 символов")
        String city,
        String password
) {

}
