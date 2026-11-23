package ru.otus.socialnetwork.api;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.socialnetwork.api.BearerAuthentication.AuthenticatedUser;
import ru.otus.socialnetwork.api.dto.PostResponse;
import ru.otus.socialnetwork.service.PostFeedService;
import ru.otus.socialnetwork.service.PostService;
import ru.otus.socialnetwork.validation.PostIds;

@RestController
@RequestMapping("/post")
public class PostController {
    private final PostService posts;
    private final PostFeedService feed;

    public PostController(PostService posts, PostFeedService feed) {
        this.posts = posts;
        this.feed = feed;
    }

    @GetMapping("/feed")
    public List<PostResponse> feed(AuthenticatedUser user,
                                   @RequestParam(name = "offset", required = false) String offset,
                                   @RequestParam(name = "limit", required = false) String limit) {
        int first = parsePageArgument(offset, "offset", 0, 0);
        int count = parsePageArgument(limit, "limit", 10, 1);
        return feed.getPage(user.id(), first, count).stream().map(PostResponse::from).toList();
    }

    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public UUID create(AuthenticatedUser user, @RequestBody CreatePostRequest request) {
        return posts.create(user.id(), request.text());
    }

    @PutMapping(value = "/update", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void update(AuthenticatedUser user, @RequestBody UpdatePostRequest request) {
        posts.update(user.id(), PostIds.parse(request.id()), request.text());
    }

    @PutMapping("/delete/{id}")
    public void delete(AuthenticatedUser user, @PathVariable String id) {
        posts.delete(user.id(), PostIds.parse(id));
    }

    private static int parsePageArgument(String value, String field, int defaultValue, int minimum) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // The same error covers invalid integers and values outside the allowed range.
        }
        throw ApiException.badRequest("Параметр должен быть целым числом не меньше " + minimum, field);
    }

    public record CreatePostRequest(String text) {}

    public record UpdatePostRequest(String id, String text) {}
}
