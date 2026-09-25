package com.urlshortener.common.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-replica routing (design doc section 3.2, F4). Active only when {@code app.datasource.replica.url} is set; without it
 * the application uses the single Boot-configured DataSource and behaves identically (one primary).
 *
 * <p>Read-only transactions go to the replica, everything else to the primary. Only the redirect fallback and the stats
 * aggregate reads are read-only; ownership/metadata reads and every write use the primary, so an owner always reads their
 * own writes even under replica lag. Replica lag is accepted for the redirect fallback (design doc F4): a code, once
 * created, is rarely mutated, and the cache absorbs the window right after creation.
 *
 * <p>{@link LazyConnectionDataSourceProxy} is essential: Spring applies the read-only flag <em>after</em> it begins the
 * transaction, so the connection must be acquired lazily (on first statement) for the routing decision to see it.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.datasource.replica", name = "url")
class ReplicaRoutingConfig {

    private static final String PRIMARY = "primary";
    private static final String REPLICA = "replica";

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource primaryDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * Built from {@code app.datasource.replica.*} directly (not as a second {@code DataSourceProperties} bean, which would
     * make Boot's own injection ambiguous). Pool tuning is shared with the primary via {@code spring.datasource.hikari.*}.
     */
    @Bean
    HikariDataSource replicaDataSource(Environment env) {
        HikariDataSource replica = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(env.getRequiredProperty("app.datasource.replica.url"))
                .username(env.getProperty("app.datasource.replica.username", env.getProperty("spring.datasource.username")))
                .password(env.getProperty("app.datasource.replica.password", env.getProperty("spring.datasource.password")))
                .build();
        Binder.get(env).bind("spring.datasource.hikari", Bindable.ofInstance(replica));
        replica.setPoolName("replica");
        return replica;
    }

    @Bean
    @Primary
    DataSource dataSource(HikariDataSource primaryDataSource, HikariDataSource replicaDataSource) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
            }
        };
        Map<Object, Object> targets = new HashMap<>();
        targets.put(PRIMARY, primaryDataSource);
        targets.put(REPLICA, replicaDataSource);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(primaryDataSource);
        routing.afterPropertiesSet();
        return new LazyConnectionDataSourceProxy(routing);
    }
}
