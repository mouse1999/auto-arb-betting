package com.mouse.bet.interceptor;

import okhttp3.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Simple HTTP logging interceptor with timing breakdown
 */
@Slf4j
public class SimpleHttpLoggingInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // Log request start
        log.info("→ {} {}", request.method(), request.url());

        // Measure total time
        long totalStartNs = System.nanoTime();

        Response response;
        try {
            response = chain.proceed(request);
        } catch (Exception e) {
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);
            log.error("← FAILED after {}ms: {}", totalMs, e.getMessage());
            throw e;
        }

        // Get response but don't read body yet
        long networkEndNs = System.nanoTime();
        long networkMs = TimeUnit.NANOSECONDS.toMillis(networkEndNs - totalStartNs);

        ResponseBody body = response.body();
        long contentLength = body != null ? body.contentLength() : -1;

        // Read body to measure parsing time
        long bodyStartNs = System.nanoTime();
        String bodyString = body != null ? body.string() : "";
        long bodyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bodyStartNs);

        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);

        // Log response with breakdown
        log.info("← {} {} | Total: {}ms [Network: {}ms, Body Read: {}ms] | Size: {} bytes",
                response.code(),
                request.url().encodedPath(),
                totalMs,
                networkMs,
                bodyMs,
                bodyString.length());

        // Return response with new body (since we consumed the original)
        return response.newBuilder()
                .body(ResponseBody.create(bodyString, body != null ? body.contentType() : null))
                .build();
    }
}