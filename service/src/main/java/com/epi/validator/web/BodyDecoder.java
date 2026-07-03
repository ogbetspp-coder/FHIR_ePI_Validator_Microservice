package com.epi.validator.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes a request body to text using the correct charset instead of assuming UTF-8, so a
 * non-UTF-8 document (e.g. an ISO-8859-1 EU SmPC with accented characters) is validated as
 * written rather than silently corrupted into U+FFFD. Precedence: a byte-order mark, then the
 * Content-Type {@code charset} parameter, then an XML encoding declaration, then UTF-8.
 * Decoding is strict: an undecodable body fails with 400 rather than validating garbage.
 */
public final class BodyDecoder {

    private static final Pattern XML_ENCODING =
            Pattern.compile("<\\?xml[^>]*\\bencoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private BodyDecoder() {
    }

    public static String decode(byte[] body, String contentType) {
        Charset charset = charsetFromBom(body);
        int offset = bomLength(body);
        if (charset == null) {
            charset = charsetFromContentType(contentType);
        }
        if (charset == null && isXml(contentType)) {
            charset = charsetFromXmlDeclaration(body);
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body, offset, body.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body is not valid " + charset.name() + " text");
        }
    }

    private static Charset charsetFromBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        return null;
    }

    private static int bomLength(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF
                || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)) {
            return 2;
        }
        return 0;
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            return MediaType.parseMediaType(contentType).getCharset();
        } catch (RuntimeException e) {
            return null; // malformed Content-Type: fall through to other detection
        }
    }

    private static boolean isXml(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("xml");
    }

    private static Charset charsetFromXmlDeclaration(byte[] body) {
        // The XML declaration is ASCII-compatible in every supported encoding, so reading the
        // leading bytes as ISO-8859-1 safely exposes the encoding="..." attribute.
        int len = Math.min(body.length, 128);
        String head = new String(Arrays.copyOf(body, len), StandardCharsets.ISO_8859_1);
        Matcher m = XML_ENCODING.matcher(head);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                return null;
            }
        }
        return null;
    }
}
