package com.wallet.ledger.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI walletLedgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet Ledger API")
                        .version("v1")
                        .description("Production-minded wallet ledger: credits, debits, "
                                + "idempotent money movement and append-only transaction history.")
                        .contact(new Contact().name("Wallet Ledger Service"))
                        .license(new License().name("MIT")));
    }
}
