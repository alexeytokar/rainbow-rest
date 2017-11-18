package ua.net.tokar.json.filters;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
public class GroupsController {
    @RequestMapping( "/groups" )
    public List<Group> groupsList() {
        return Arrays.asList(
                new Group( 2, "bad group", new Link( "/groups/2/users", "users" ) ),
                new Group( 5, "good group", new Link( "/groups/5/users", "users" ) )
        );
    }

    @RequestMapping( "/groups/{id}/users" )
    public List<User> usersOfAGroup(
            @PathVariable Integer id
    ) {
        return Arrays.asList(
                new User( "boss", new Link( "/users/1/friends", "friends" ) ),
                new User( "cat", new Link( "/users/2/friends", "friends" ) ),
                new User( "dog", new Link( "/users/42/friends", "friends" ) )
        );
    }

    @RequestMapping( "/users/{id}/friends" )
    public List<User> getFriends(
            @PathVariable Integer id
    ) {
        return Arrays.asList(
                new User( "boss", new Link( "/users/1/friends", "friends" ) ),
                new User( "cat", new Link( "/users/2/friends", "friends" ) ),
                new User( "dog", new Link( "/users/42/friends", "friends" ) )
        );
    }
}
