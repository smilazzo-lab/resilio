package io.resilio.redis.connection;

import java.time.Duration;

/**
 * Configuration for Redis cache connection.
 *
 * <p>Supports single node, sentinel, and cluster configurations.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RedisCacheConfig config = RedisCacheConfig.builder()
 *     .host("localhost")
 *     .port(6379)
 *     .password("secret")
 *     .database(0)
 *     .connectionTimeout(Duration.ofSeconds(5))
 *     .commandTimeout(Duration.ofSeconds(2))
 *     .poolSize(10)
 *     .build();
 * }</pre>
 *
 * @author Salvatore Milazzo <milazzosa@gmail.com>
 * @since 1.0.0
 */
public class RedisCacheConfig {

    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final boolean ssl;
    private final Duration connectionTimeout;
    private final Duration commandTimeout;
    private final int poolMinIdle;
    private final int poolMaxIdle;
    private final int poolMaxTotal;
    private final String keyPrefix;

    // Sentinel configuration
    private final String sentinelMasterId;
    private final String[] sentinelNodes;

    // Cluster configuration
    private final String[] clusterNodes;

    private RedisCacheConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.password = builder.password;
        this.database = builder.database;
        this.ssl = builder.ssl;
        this.connectionTimeout = builder.connectionTimeout;
        this.commandTimeout = builder.commandTimeout;
        this.poolMinIdle = builder.poolMinIdle;
        this.poolMaxIdle = builder.poolMaxIdle;
        this.poolMaxTotal = builder.poolMaxTotal;
        this.keyPrefix = builder.keyPrefix;
        this.sentinelMasterId = builder.sentinelMasterId;
        this.sentinelNodes = builder.sentinelNodes;
        this.clusterNodes = builder.clusterNodes;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getPassword() { return password; }
    public int getDatabase() { return database; }
    public boolean isSsl() { return ssl; }
    public Duration getConnectionTimeout() { return connectionTimeout; }
    public Duration getCommandTimeout() { return commandTimeout; }
    public int getPoolMinIdle() { return poolMinIdle; }
    public int getPoolMaxIdle() { return poolMaxIdle; }
    public int getPoolMaxTotal() { return poolMaxTotal; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getSentinelMasterId() { return sentinelMasterId; }
    public String[] getSentinelNodes() { return sentinelNodes; }
    public String[] getClusterNodes() { return clusterNodes; }

    public boolean isSentinel() {
        return sentinelMasterId != null && sentinelNodes != null && sentinelNodes.length > 0;
    }

    public boolean isCluster() {
        return clusterNodes != null && clusterNodes.length > 0;
    }

    public boolean isStandalone() {
        return !isSentinel() && !isCluster();
    }

    /**
     * Builds the Redis URI for Lettuce client.
     *
     * @return Redis URI string
     */
    public String buildUri() {
        StringBuilder sb = new StringBuilder();
        sb.append(ssl ? "rediss://" : "redis://");

        if (password != null && !password.isEmpty()) {
            sb.append(":").append(password).append("@");
        }

        sb.append(host).append(":").append(port);
        sb.append("/").append(database);

        return sb.toString();
    }

    /**
     * Builder for RedisCacheConfig.
     */
    public static class Builder {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
        private boolean ssl = false;
        private Duration connectionTimeout = Duration.ofSeconds(10);
        private Duration commandTimeout = Duration.ofSeconds(5);
        private int poolMinIdle = 1;
        private int poolMaxIdle = 8;
        private int poolMaxTotal = 8;
        private String keyPrefix = "";
        private String sentinelMasterId;
        private String[] sentinelNodes;
        private String[] clusterNodes;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder database(int database) {
            this.database = database;
            return this;
        }

        public Builder ssl(boolean ssl) {
            this.ssl = ssl;
            return this;
        }

        public Builder connectionTimeout(Duration timeout) {
            this.connectionTimeout = timeout;
            return this;
        }

        public Builder commandTimeout(Duration timeout) {
            this.commandTimeout = timeout;
            return this;
        }

        public Builder poolMinIdle(int minIdle) {
            this.poolMinIdle = minIdle;
            return this;
        }

        public Builder poolMaxIdle(int maxIdle) {
            this.poolMaxIdle = maxIdle;
            return this;
        }

        public Builder poolMaxTotal(int maxTotal) {
            this.poolMaxTotal = maxTotal;
            return this;
        }

        /**
         * Sets poolSize as shorthand for both maxIdle and maxTotal.
         */
        public Builder poolSize(int size) {
            this.poolMaxIdle = size;
            this.poolMaxTotal = size;
            return this;
        }

        public Builder keyPrefix(String prefix) {
            this.keyPrefix = prefix != null ? prefix : "";
            return this;
        }

        /**
         * Configure Redis Sentinel mode.
         *
         * @param masterId the sentinel master name
         * @param nodes sentinel nodes in format "host:port"
         */
        public Builder sentinel(String masterId, String... nodes) {
            this.sentinelMasterId = masterId;
            this.sentinelNodes = nodes;
            return this;
        }

        /**
         * Configure Redis Cluster mode.
         *
         * @param nodes cluster nodes in format "host:port"
         */
        public Builder cluster(String... nodes) {
            this.clusterNodes = nodes;
            return this;
        }

        public RedisCacheConfig build() {
            return new RedisCacheConfig(this);
        }
    }
}
