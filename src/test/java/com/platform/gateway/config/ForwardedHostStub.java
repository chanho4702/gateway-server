package com.platform.gateway.config;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

/** auth-server 대역 — 게이트웨이가 넘긴 X-Forwarded-Host를 기록만 한다. */
final class ForwardedHostStub {

    final HttpServer server;
    final AtomicReference<String> forwardedHost = new AtomicReference<>();

    ForwardedHostStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/me", exchange -> {
            forwardedHost.set(String.valueOf(exchange.getRequestHeaders().getFirst("X-Forwarded-Host")));
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    String uri() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
