package uk.org.openseizuredetector;

// Interface used by the authentication part of WebApi to send back the authentication token
public interface AuthCallbackInterface {
        void authCallback(boolean success, String tokenStr);
}
