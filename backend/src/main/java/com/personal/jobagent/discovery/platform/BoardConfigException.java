package com.personal.jobagent.discovery.platform;

/** A board identifier or URL that cannot be used. The message is safe to show to the operator. */
public class BoardConfigException extends IllegalArgumentException {
    public BoardConfigException(String message) {
        super(message);
    }
}
