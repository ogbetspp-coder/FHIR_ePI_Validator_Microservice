package com.epi.validator.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Charset detection: a document must be decoded as written, never silently corrupted. */
class BodyDecoderTest {

    @Test
    void utf8IsTheDefault() {
        byte[] body = "café".getBytes(StandardCharsets.UTF_8);
        assertThat(BodyDecoder.decode(body, "application/fhir+json")).isEqualTo("café");
    }

    @Test
    void honoursContentTypeCharset() {
        byte[] body = "café".getBytes(StandardCharsets.ISO_8859_1); // é == 0xE9, invalid UTF-8
        assertThat(BodyDecoder.decode(body, "application/fhir+xml; charset=ISO-8859-1"))
                .isEqualTo("café");
    }

    @Test
    void honoursXmlEncodingDeclarationWhenContentTypeHasNoCharset() {
        String xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><x>café</x>";
        byte[] body = xml.getBytes(StandardCharsets.ISO_8859_1);
        String decoded = BodyDecoder.decode(body, "application/fhir+xml");
        assertThat(decoded).contains("café");
    }

    @Test
    void stripsUtf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] json = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] body = concat(bom, json);
        String decoded = BodyDecoder.decode(body, "application/fhir+json");
        assertThat(decoded).isEqualTo("{\"a\":1}").doesNotStartWith("﻿");
    }

    @Test
    void decodesUtf16ByBom() {
        byte[] bom = {(byte) 0xFE, (byte) 0xFF}; // UTF-16BE
        byte[] text = "hi".getBytes(StandardCharsets.UTF_16BE);
        assertThat(BodyDecoder.decode(concat(bom, text), "application/fhir+json")).isEqualTo("hi");
    }

    @Test
    void undecodableBodyFailsWith400NotCorruption() {
        byte[] invalidUtf8 = {(byte) 0x80, (byte) 0x81}; // lone continuation bytes
        assertThatThrownBy(() -> BodyDecoder.decode(invalidUtf8, "application/fhir+json"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void malformedContentTypeFallsBackToUtf8() {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        assertThat(BodyDecoder.decode(body, "not a media type ;;;")).isEqualTo("ok");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
