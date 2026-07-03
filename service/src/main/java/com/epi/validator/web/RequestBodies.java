package com.epi.validator.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Reads a request body into memory with a hard size ceiling, inflating {@code Content-Encoding:
 * gzip} (Cloud Run does not do this for the app). Both the compressed read and the inflated
 * result are bounded, so neither a large upload nor a gzip bomb can exhaust the heap. The
 * ceiling is enforced while streaming, before the whole body is buffered.
 */
final class RequestBodies {

    private RequestBodies() {
    }

    static byte[] read(HttpServletRequest request, long maxBytes) {
        byte[] raw;
        try {
            raw = readBounded(request.getInputStream(), maxBytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Could not read request body: " + e.getMessage());
        }
        String encoding = request.getHeader("Content-Encoding");
        if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            return raw;
        }
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return readBounded(gz, maxBytes);
        } catch (ApiException e) {
            throw e; // 413 from the inflated-size ceiling must not be masked as a gzip error
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body is not valid gzip: " + e.getMessage());
        }
    }

    static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body exceeds the configured limit of " + (maxBytes / (1024 * 1024)) + " MB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
