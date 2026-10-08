package com.aryabank.payments.api;

import static com.aryabank.payments.support.PaymentsApi.request;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@DisplayName("Health")
class HealthTest {

    @Test
    @DisplayName("Health endpoint reports UP")
    void healthIsUp() {
        request().get("/health").then().statusCode(200).body("status", equalTo("UP"));
    }
}
