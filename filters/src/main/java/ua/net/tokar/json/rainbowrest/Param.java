package ua.net.tokar.json.rainbowrest;

public class Param {
    private final String name;
    private final String value;

    public Param( String name, String value ) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }
}
