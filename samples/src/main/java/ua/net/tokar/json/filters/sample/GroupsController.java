package ua.net.tokar.json.filters.sample;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
            @PathVariable Integer id,
            @RequestParam( defaultValue = "3" ) int limit,
            @RequestParam( defaultValue = "0" ) int offset
    ) {
        return Stream.of(
                new User( "boss", new Link( "/users/1/friends", "friends" ) ),
                new User( "cat", new Link( "/users/2/friends", "friends" ) ),
                new User( "dog", new Link( "/users/42/friends", "friends" ) )
        )
                     .skip( offset )
                     .limit( limit )
                     .collect( Collectors.toList() );
    }

    @RequestMapping( "/users" )
    public HashMap<String, List<User>> getUsers() {
        HashMap<String, List<User>> userWrapper = new HashMap<>();
        List<User> users = Arrays.asList(
                new User( "boss", new Link( "/users/1/friends", "friends" ) ),
                new User( "cat", new Link( "/users/2/friends", "friends" ) ),
                new User( "dog", new Link( "/users/42/friends", "friends" ) )
        );
        userWrapper.put( "users", users );
        return userWrapper;
    }

    @RequestMapping( "/users/{id}/friends" )
    public List<User> getFriends(
            @PathVariable Integer id,
            @RequestParam( defaultValue = "3" ) int limit,
            @RequestParam( defaultValue = "0" ) int offset
    ) {
        return Stream.of(
                new User( "boss", new Link( "/users/1/friends", "friends" ) ),
                new User( "cat", new Link( "/users/2/friends", "friends" ) ),
                new User( "dog", new Link( "/users/42/friends", "friends" ) )
        )
                     .skip( offset )
                     .limit( limit )
                     .collect( Collectors.toList() );
    }
}
