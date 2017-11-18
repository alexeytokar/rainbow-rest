package ua.net.tokar.json.filters;

public class Group {
    public final Integer id;
    public final String title;
    public final Link users;

    public Group( Integer id, String title, Link users ) {
        this.id = id;
        this.title = title;
        this.users = users;
    }
}

