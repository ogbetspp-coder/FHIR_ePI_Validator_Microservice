package com.epi.validator.web;

import com.epi.validator.config.EpiValidationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Transparently inflates {@code Content-Encoding: gzip} request bodies (Cloud Run does not do
 * this for the application) and enforces the post-inflation size cap. Oversized bodies fail
 * with 413 regardless of encoding — base64 PDFs in Binary resources make ePI payloads large.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class GzipRequestFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public GzipRequestFilter(EpiValidationProperties properties) {
        this.maxBodyBytes = properties.maxBodyMb() * 1024L * 1024L;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String encoding = request.getHeader("Content-Encoding");
        boolean gzip = encoding != null && encoding.toLowerCase().contains("gzip");
        if (!gzip && request.getContentLengthLong() > maxBodyBytes) {
            throw new PayloadTooLargeException(maxBodyBytes);
        }
        if (gzip || "POST".equalsIgnoreCase(request.getMethod())) {
            request = new LimitedBodyRequest(request, gzip, maxBodyBytes);
        }
        filterChain.doFilter(request, response);
    }

    static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final boolean gzip;
        private final long limit;

        LimitedBodyRequest(HttpServletRequest request, boolean gzip, long limit) {
            super(request);
            this.gzip = gzip;
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            InputStream base = super.getInputStream();
            InputStream effective = gzip ? new GZIPInputStream(base) : base;
            return new LimitedServletInputStream(effective, limit);
        }

        @Override
        public int getContentLength() {
            return gzip ? -1 : super.getContentLength();
        }

        @Override
        public long getContentLengthLong() {
            return gzip ? -1 : super.getContentLengthLong();
        }

        // The original Content-Length describes the COMPRESSED body. It must be masked from
        // the header view too, or Spring's message converters truncate the inflated stream
        // at the compressed length (readNBytes(contentLength)).
        private boolean masked(String name) {
            return gzip && ("content-length".equalsIgnoreCase(name) || "content-encoding".equalsIgnoreCase(name));
        }

        @Override
        public String getHeader(String name) {
            return masked(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return masked(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public int getIntHeader(String name) {
            return masked(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            if (!gzip) {
                return super.getHeaderNames();
            }
            List<String> names = new ArrayList<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();
                if (!masked(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }

    static final class LimitedServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private final long limit;
        private long read;

        LimitedServletInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            read += n;
            if (read > limit) {
                throw new PayloadTooLargeException(limit);
            }
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
            throw new UnsupportedOperationException("Async body reading not supported");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(long limitBytes) {
            super("Request body exceeds the configured limit of " + (limitBytes / (1024 * 1024))
                    + " MB (post gzip inflation)");
        }
    }
}
