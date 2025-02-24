package org.logstash.snmp;

import org.logstash.snmp.mib.MibManager;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.logstash.snmp.SnmpUtils.parseAuthProtocol;
import static org.logstash.snmp.SnmpUtils.parseNullableOctetString;
import static org.logstash.snmp.SnmpUtils.parsePrivProtocol;

public final class SnmpClientBuilder {
    private final MibManager mib;
    private final int port;
    private OctetString localEngineId = new OctetString(MPv3.createLocalEngineID());
    private final Set<String> supportedTransports;
    private Set<Integer> supportedVersions = Set.of(SnmpConstants.version1, SnmpConstants.version2c, SnmpConstants.version3);
    private String host = "0.0.0.0";
    private final List<UsmUser> usmUsers = new ArrayList<>();
    private int threadPoolSize = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private String threadPoolName = "SnmpWorker";
    private boolean mapOidVariableValues = false;

    public SnmpClientBuilder(MibManager mib, Set<String> supportedTransports, int port) {
        this.mib = mib;
        this.supportedTransports = supportedTransports;
        this.port = port;
    }

    public SnmpClientBuilder setHost(final String host) {
        this.host = host;
        return this;
    }

    public SnmpClientBuilder setLocalEngineId(final String localEngineId) {
        this.localEngineId = new OctetString(localEngineId);
        return this;
    }

    public SnmpClientBuilder addUsmUser(
            String securityName,
            String authProtocol,
            String authPassphrase,
            String privProtocol,
            String privPassphrase
    ) {
        this.usmUsers.add(new UsmUser(
                new OctetString(securityName),
                parseAuthProtocol(authProtocol),
                parseNullableOctetString(authPassphrase),
                parsePrivProtocol(privProtocol),
                parseNullableOctetString(privPassphrase)
        ));

        return this;
    }

    public SnmpClientBuilder setThreadPoolName(final String threadPoolName) {
        this.threadPoolName = threadPoolName;
        return this;
    }

    public SnmpClientBuilder setThreadPoolSize(final int threadPoolSize) {
        this.threadPoolSize = Math.max(1, threadPoolSize);
        return this;
    }

    public SnmpClientBuilder setSupportedVersions(final Set<String> supportedVersions) {
        final Set<Integer> versions = supportedVersions
                .stream()
                .map(SnmpUtils::parseSnmpVersion)
                .collect(Collectors.toCollection(HashSet::new));

        if (versions.isEmpty()) {
            throw new IllegalArgumentException("at least one SNMP version must be supported");
        }

        this.supportedVersions = versions;
        return this;
    }

    public SnmpClientBuilder setMapOidVariableValues(final boolean mapOidVariableValues) {
        this.mapOidVariableValues = mapOidVariableValues;
        return this;
    }

    public SnmpClient build() throws IOException {
        return new SnmpClient(
                mib,
                supportedTransports,
                supportedVersions,
                host,
                port,
                threadPoolName,
                threadPoolSize,
                usmUsers,
                localEngineId,
                mapOidVariableValues
        );
    }
}
