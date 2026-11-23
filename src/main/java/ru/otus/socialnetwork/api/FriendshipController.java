package ru.otus.socialnetwork.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.otus.socialnetwork.api.BearerAuthentication.AuthenticatedUser;
import ru.otus.socialnetwork.service.FriendshipService;
import ru.otus.socialnetwork.validation.UserIds;

@RestController
@RequestMapping("/friend")
public class FriendshipController {
    private final FriendshipService friendships;

    public FriendshipController(FriendshipService friendships) {
        this.friendships = friendships;
    }

    @PutMapping("/set/{user_id}")
    public void add(AuthenticatedUser user, @PathVariable("user_id") String friendId) {
        friendships.add(user.id(), UserIds.parse(friendId, "user_id"));
    }

    @PutMapping("/delete/{user_id}")
    public void delete(AuthenticatedUser user, @PathVariable("user_id") String friendId) {
        friendships.delete(user.id(), UserIds.parse(friendId, "user_id"));
    }
}
