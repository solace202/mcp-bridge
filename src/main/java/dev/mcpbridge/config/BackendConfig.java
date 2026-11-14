package dev.mcpbridge.config;

import dev.mcpbridge.backend.JdbcShopBackend;
import dev.mcpbridge.backend.RestShopBackend;
import dev.mcpbridge.shop.ShopBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class BackendConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendConfig.class);

    /** The shop's own data access; serves the shop REST API in this demo and the bridge in local mode. */
    @Bean
    JdbcShopBackend jdbcShopBackend(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        return new JdbcShopBackend(jdbc, tx);
    }

    /** What the MCP tools use. */
    @Bean
    @Primary
    ShopBackend bridgeBackend(BridgeProperties props, JdbcShopBackend local, RestClient.Builder builder) {
        var cfg = props.backend();
        if (cfg.type() == BridgeProperties.BackendType.LOCAL) {
            log.info("MCP tools use the shop database directly (bridge.backend.type=local)");
            return local;
        }
        if (!StringUtils.hasText(cfg.baseUrl())) {
            throw new IllegalStateException("bridge.backend.base-url is required when bridge.backend.type=rest");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.timeout());
        factory.setReadTimeout(cfg.timeout());
        RestClient.Builder b = builder.clone().baseUrl(cfg.baseUrl()).requestFactory(factory);
        if (StringUtils.hasText(cfg.token())) {
            b.defaultHeader("X-Shop-Token", cfg.token());
        }
        log.info("MCP tools call the shop API at {} (bridge.backend.type=rest)", cfg.baseUrl());
        return new RestShopBackend(b.build());
    }
}
