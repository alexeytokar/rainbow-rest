package ua.net.tokar.json.rainbowrest.exception;

public class RequestProcessingTimedOutException extends RuntimeException {
    public RequestProcessingTimedOutException() {
    }

    public RequestProcessingTimedOutException( Throwable cause ) {
        super( cause );
    }
}
