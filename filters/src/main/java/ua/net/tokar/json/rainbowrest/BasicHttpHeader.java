package ua.net.tokar.json.rainbowrest;

public class BasicHttpHeader implements HttpHeader {
    private String name;
    private String value;

    public BasicHttpHeader( String name, String value ) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getValue() {
        return value;
    }
}
