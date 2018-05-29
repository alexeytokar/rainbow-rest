package ua.net.tokar.json.rainbowrest.exception;

public class RequestProcessingFailedException extends RuntimeException {
    public RequestProcessingFailedException() {
    }

    public RequestProcessingFailedException( Throwable cause ) {
        super( cause );
    }
}
