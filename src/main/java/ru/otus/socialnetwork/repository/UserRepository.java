package ru.otus.socialnetwork.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.otus.socialnetwork.domain.Gender;
import ru.otus.socialnetwork.domain.UserProfile;

@Repository
public class UserRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int insert(String firstName, String secondName, LocalDate birthdate,
                      Gender gender, String biography, String city) {
        String sql = """
                INSERT INTO users (first_name, second_name, birthdate, gender, biography, city)
                VALUES (:firstName, :secondName, :birthdate, CAST(:gender AS user_gender), :biography, :city)
                RETURNING id
                """;
        var parameters = new MapSqlParameterSource()
                .addValue("firstName", firstName)
                .addValue("secondName", secondName)
                .addValue("birthdate", Date.valueOf(birthdate))
                .addValue("gender", gender.name())
                .addValue("biography", biography, Types.VARCHAR)
                .addValue("city", city, Types.VARCHAR);
        Integer id = jdbc.queryForObject(sql, parameters, Integer.class);
        if (id == null) { throw new IllegalStateException("INSERT did not return a user ID"); }
        return id;
    }

    public Optional<UserProfile> findById(int id) {
        String sql = """
                SELECT id, first_name, second_name, birthdate, gender, biography, city
                FROM users WHERE id = :id
                """;
        return jdbc.query(sql, Map.of("id", id), UserRepository::mapProfile).stream().findFirst();
    }

    public List<UserProfile> searchByPrefixes(String firstNamePattern, String secondNamePattern) {
        String sql = """
                SELECT id, first_name, second_name, birthdate, gender, biography, city
                FROM users
                WHERE first_name LIKE :firstName ESCAPE '!'
                  AND second_name LIKE :secondName ESCAPE '!'
                ORDER BY id ASC
                """;
        return jdbc.query(sql, Map.of("firstName", firstNamePattern, "secondName", secondNamePattern),
                UserRepository::mapProfile);
    }

    private static UserProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new UserProfile(
                rs.getInt("id"), rs.getString("first_name"), rs.getString("second_name"),
                rs.getObject("birthdate", LocalDate.class), Gender.valueOf(rs.getString("gender")),
                rs.getString("biography"), rs.getString("city"));
    }
}
