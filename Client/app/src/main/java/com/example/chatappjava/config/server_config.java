package com.example.chatappjava.config;

public class server_config {
    public static final String BASE_URL = "http://192.168.1.14:8000"; // Địa chỉ REST API backend
    public static final String WS_URL = "ws://192.168.1.14:8000/ws/"; // Địa chỉ WebSocket signaling

    // TURN server for NAT traversal (required for physical device ↔ emulator calls)
    public static final String TURN_URL_UDP = "turn:openrelay.metered.ca:80?transport=udp";
    public static final String TURN_URL_TCP = "turn:openrelay.metered.ca:80?transport=tcp";
    public static final String TURN_USERNAME = "openrelayproject";
    public static final String TURN_PASSWORD = "openrelayproject";
}
