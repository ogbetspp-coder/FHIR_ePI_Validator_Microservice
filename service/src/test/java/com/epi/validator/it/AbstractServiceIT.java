package com.epi.validator.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Shared @SpringBootTest configuration so all integration tests reuse ONE application context
 * (one ~40s validator warm-up for the whole suite). Keep the annotations identical in meaning
 * by inheriting from this class only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractServiceIT {

    @Autowired
    protected TestRestTemplate rest;
}
