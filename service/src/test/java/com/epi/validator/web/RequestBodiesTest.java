package com.epi.validator.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The crash-critical request-body path: bodies are bounded while streaming, gzip is inflated
 * with the same ceiling (bomb-safe), and malformed input fails cleanly with the right status —
 * never an unbounded buffer or an ambiguous 500.
 */
class RequestBodiesTest {

    private static final long CAP = 1024; // 1 KB for tests

    private static HttpServletRequest request(byte[] body, String contentEncoding) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getInputStream()).thenReturn(servletStream(new ByteArrayInputStream(body)));
        when(request.getHeader("Content-Encoding")).thenReturn(contentEncoding);
        return request;
    }

    private static ServletInputStream servletStream(InputStream delegate) {
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return delegate.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }
        };
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    @Test
    void readsPlainBodyUnderTheCap() throws IOException {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        assertThat(RequestBodies.read(request(body, null), CAP)).isEqualTo(body);
    }

    @Test
    void readsEmptyBody() throws IOException {
        assertThat(RequestBodies.read(request(new byte[0], null), CAP)).isEmpty();
    }

    @Test
    void rejectsPlainBodyOverTheCapWith413() throws IOException {
        byte[] body = new byte[(int) CAP + 1];
        assertThatThrownBy(() -> RequestBodies.read(request(body, null), CAP))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void inflatesGzipBody() throws IOException {
        byte[] payload = "the quick brown fox".repeat(10).getBytes(StandardCharsets.UTF_8);
        byte[] result = RequestBodies.read(request(gzip(payload), "gzip"), CAP);
        assertThat(result).isEqualTo(payload);
    }

    @Test
    void rejectsGzipBombWith413OnInflatedSize() throws IOException {
        // Compresses tiny, inflates far past the cap — must fail on the inflated ceiling, not OOM.
        byte[] payload = new byte[64 * 1024];
        byte[] compressed = gzip(payload);
        assertThat(compressed.length).isLessThan((int) CAP); // the compressed body slips under the cap
        assertThatThrownBy(() -> RequestBodies.read(request(compressed, "gzip"), CAP))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void rejectsMalformedGzipWith400() throws IOException {
        byte[] notGzip = "this is definitely not gzip".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RequestBodies.read(request(notGzip, "gzip"), CAP))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void contentEncodingMatchIsCaseInsensitive() throws IOException {
        byte[] payload = "gzip case test".getBytes(StandardCharsets.UTF_8);
        assertThat(RequestBodies.read(request(gzip(payload), "GZIP"), CAP)).isEqualTo(payload);
    }
}
