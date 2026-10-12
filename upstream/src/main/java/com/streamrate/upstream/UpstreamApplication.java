package com.streamrate.upstream;

import java.net.InetAddress;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tiny backend that the gateway protects. Not part of the rate limiter itself. */
@SpringBootApplication
@RestController
public class UpstreamApplication {

    private static final String HOST = hostName();

    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        return Map.of("message", "hello from upstream", "host", HOST);
    }

    @GetMapping("/secure/hello")
    public Map<String, String> secureHello() {
        return Map.of("message", "hello from the protected endpoint", "host", HOST);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(UpstreamApplication.class, args);
    }
}
