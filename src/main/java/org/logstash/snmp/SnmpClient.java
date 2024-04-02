package org.logstash.snmp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.logstash.snmp.mib.MibManager;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.TSM;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.AssignableFromInteger;
import org.snmp4j.smi.AssignableFromLong;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.SMIConstants;
import org.snmp4j.smi.TcpAddress;
import org.snmp4j.smi.TlsAddress;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.AbstractTransportMapping;
import org.snmp4j.transport.DefaultTcpTransportMapping;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.transport.TLSTM;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.MultiThreadedMessageDispatcher;
import org.snmp4j.util.PDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;
import org.snmp4j.util.ThreadPool;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.nonNull;
import static org.logstash.snmp.SnmpUtils.parseSecurityLevel;
import static org.logstash.snmp.SnmpUtils.parseSnmpVersion;

public class SnmpClient implements Closeable {
    private static final Logger logger = LogManager.getLogger(SnmpClient.class);
    private final MibManager mib;
    private final Snmp snmp;
    private final OctetString contextEngineId;
    private final OctetString contextName;

    public static SnmpClientBuilder builder(MibManager mib, Set<String> protocols) {
        return new SnmpClientBuilder(mib, protocols, 0);
    }

    public static SnmpClientBuilder builder(MibManager mib, Set<String> protocols, int port) {
        return new SnmpClientBuilder(mib, protocols, port);
    }

    SnmpClient(
            MibManager mib,
            Set<String> protocols,
            String host,
            int port,
            String threadPoolName,
            int threadPoolSize,
            List<UsmUser> users,
            OctetString localEngineId,
            OctetString contextEngineId,
            OctetString contextName
    ) throws IOException {
        this.mib = mib;
        this.contextEngineId = contextEngineId;
        this.contextName = contextName;

        // global security models/protocols
        SecurityProtocols.getInstance().addDefaultProtocols();
        SecurityProtocols.getInstance().addPrivacyProtocol(new Priv3DES());
        SecurityModels.getInstance().addSecurityModel(new TSM(localEngineId, false));

        this.snmp = createSnmpClient(protocols, host, port, localEngineId, users, threadPoolName, threadPoolSize);
    }

    private static Snmp createSnmpClient(
            Set<String> protocols,
            String host,
            int port,
            OctetString localEngineId,
            List<UsmUser> usmUsers,
            String threadPoolName,
            int threadPoolSize
    ) throws IOException {
        final int engineBootCount = 0;
        final USM usm = createUsm(usmUsers, localEngineId, engineBootCount);
        final Snmp snmp = new Snmp(createMessageDispatcher(usm, engineBootCount, threadPoolName, threadPoolSize));

        for (final String protocol : protocols) {
            snmp.addTransportMapping(createTransport(parseAddress(protocol, host, port)));
        }

        return snmp;
    }

    private static USM createUsm(List<UsmUser> usmUsers, OctetString localEngineID, int engineBootCount) {
        final USM usm = new USM(SecurityProtocols.getInstance(), localEngineID, engineBootCount);

        if (usmUsers != null) {
            usmUsers.forEach(usm::addUser);
        }

        return usm;
    }

    private static MessageDispatcher createMessageDispatcher(
            USM usm,
            int engineBootCount,
            String threadPoolName,
            int threadPoolSize
    ) {
        final ThreadPool threadPool = ThreadPool.create(threadPoolName, threadPoolSize);
        final MessageDispatcher dispatcher = new MultiThreadedMessageDispatcher(threadPool, new MessageDispatcherImpl());

        dispatcher.addMessageProcessingModel(new MPv1());
        dispatcher.addMessageProcessingModel(new MPv2c());

        final MPv3 mpv3 = new MPv3(usm);
        mpv3.setCurrentMsgID(MPv3.randomMsgID(engineBootCount));
        dispatcher.addMessageProcessingModel(mpv3);

        return dispatcher;
    }

    public void listen() throws IOException {
        getSnmp().listen();
    }

    public Map<String, Object> get(Target target, OID[] oids) throws IOException {
        final PDU pdu = createPDU(target, PDU.GET);
        pdu.addAll(VariableBinding.createFromOIDs(oids));

        final ResponseEvent responseEvent = getSnmp().send(pdu, target);
        if (responseEvent == null) {
            return Collections.emptyMap();
        }

        final Exception error = responseEvent.getError();
        if (error != null) {
            throw new SnmpClientException(
                    String.format("error sending snmp get request to target %s: %s", target.getAddress(), error.getMessage()),
                    error
            );
        }

        final PDU responsePdu = responseEvent.getResponse();
        if (responsePdu == null) {
            throw new SnmpClientException(String.format("timeout sending snmp get request to target %s", target.getAddress()));
        }

        final HashMap<String, Object> result = new HashMap<>();
        for (VariableBinding binding : responsePdu.getVariableBindings()) {
            final String oid = mib.map(binding.getOid());
            result.put(oid, coerceVariable(binding.getVariable()));
        }

        return result;
    }

    public Map<String, Object> walk(Target target, OID oid) {
        final TreeUtils treeUtils = createGetTreeUtils();
        final List<TreeEvent> events = treeUtils.getSubtree(target, oid);

        if (events == null || events.isEmpty()) {
            return Collections.emptyMap();
        }

        final HashMap<String, Object> result = new HashMap<>();
        for (final TreeEvent event : events) {
            if (event == null) {
                continue;
            }

            if (event.isError()) {
                throw new SnmpClientException(
                        String.format("error sending snmp walk request to target %s: %s", target.getAddress(), event.getErrorMessage()),
                        event.getException()
                );
            }

            final VariableBinding[] variableBindings = event.getVariableBindings();
            if (variableBindings == null) {
                continue;
            }

            for (final VariableBinding variableBinding : variableBindings) {
                if (variableBinding == null) {
                    continue;
                }

                result.put(
                        mib.map(variableBinding.getOid()),
                        coerceVariable(variableBinding.getVariable())
                );
            }
        }

        return result;
    }

    TreeUtils createGetTreeUtils() {
        return new TreeUtils(getSnmp(), creatPDUFactory(PDU.GET));
    }

    public Map<String, List<Map<String, Object>>> table(Target target, String tableName, Collection<OID> oids) {
        final TableUtils tableUtils = createGetTableUtils();
        final List<TableEvent> events = tableUtils.getTable(target, oids.toArray(new OID[0]), null, null);

        if (events == null || events.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<Map<String, Object>> rows = new ArrayList<>(events.size());
        for (final TableEvent event : events) {
            if (event == null) {
                continue;
            }

            if (event.isError()) {
                throw new SnmpClientException(
                        String.format("error sending snmp table request to target %s: %s", target.getAddress(), event.getErrorMessage()),
                        event.getException()
                );
            }

            final VariableBinding[] variableBindings = event.getColumns();
            if (variableBindings == null || variableBindings.length == 0) {
                continue;
            }

            final HashMap<String, Object> row = new HashMap<>();
            row.put("index", event.getIndex().toString());

            for (final VariableBinding binding : variableBindings) {
                if (binding == null) {
                    continue;
                }

                final String mappedOid = mib.map(binding.getOid());
                final Object value = coerceVariable(binding.getVariable());
                row.put(mappedOid, value);
            }

            rows.add(row);
        }

        return Collections.singletonMap(tableName, rows);
    }

    TableUtils createGetTableUtils() {
        return new TableUtils(getSnmp(), creatPDUFactory(PDU.GET));
    }

    Object coerceVariable(Variable variable) {
        if (variable.isException()) {
            switch (variable.getSyntax()) {
                case SMIConstants.EXCEPTION_NO_SUCH_INSTANCE:
                    return "error: no such instance currently exists at this OID";
                case SMIConstants.EXCEPTION_NO_SUCH_OBJECT:
                    return "error: no such object currently exists at this OID";
                case SMIConstants.EXCEPTION_END_OF_MIB_VIEW:
                    return "end of MIB view";
                default:
                    return String.format("error: %s", variable.getSyntaxString());
            }
        }

        if (variable.getSyntax() == SMIConstants.SYNTAX_NULL) {
            return "null";
        }

        // Counter, Gauges, TimeTicks, etc
        if (variable instanceof AssignableFromLong) {
            return variable.toLong();
        }

        // Integer32
        if (variable instanceof AssignableFromInteger) {
            return variable.toInt();
        }

        try {
            return variable.toString();
        } catch (Exception e) {
            String message = String.format("error: unable to read variable value. Syntax: %d (%s)", variable.getSyntax(), variable.getSyntaxString());
            logger.error(message);
            return message;
        }
    }

    public Target createTarget(
            String address,
            String version,
            int retries,
            int timeout,
            String community,
            String securityName,
            String securityLevel
    ) {
        final int snmpVersion = parseSnmpVersion(version);
        final Target target;

        if (snmpVersion == SnmpConstants.version3) {
            Objects.requireNonNull(securityName, "security_name is required");
            Objects.requireNonNull(securityLevel, "security_level is required");

            target = new UserTarget();
            target.setSecurityLevel(parseSecurityLevel(securityLevel));
            target.setSecurityName(new OctetString(securityName));
            if (address.startsWith("tls")) {
                target.setSecurityModel(SecurityModel.SECURITY_MODEL_TSM);
            }
        } else {
            Objects.requireNonNull(community, "community is required");
            target = new CommunityTarget();
            ((CommunityTarget) target).setCommunity(new OctetString(community));
        }

        target.setAddress(GenericAddress.parse(address));
        target.setVersion(snmpVersion);
        target.setRetries(retries);
        target.setTimeout(timeout);

        return target;
    }

    private PDUFactory creatPDUFactory(int pduType) {
        return new DefaultPDUFactory(pduType);
    }

    private PDU createPDU(Target target, int pduType) {
        final PDU pdu;
        if (target.getVersion() == SnmpConstants.version3) {
            pdu = new ScopedPDU();
            ScopedPDU scopedPDU = (ScopedPDU) pdu;
            if (contextEngineId != null) {
                scopedPDU.setContextEngineID(contextEngineId);
            }
            if (contextName != null) {
                scopedPDU.setContextName(contextName);
            }
        } else {
            if (pduType == PDU.V1TRAP) {
                pdu = new PDUv1();
            } else {
                pdu = new PDU();
            }
        }

        pdu.setType(pduType);
        return pdu;
    }

    private static Address parseAddress(String protocol, String host, int port) {
        final String actualProtocol = nonNull(protocol) ? protocol.toLowerCase() : "udp";
        final String actualHost = nonNull(host) ? host : "0.0.0.0";
        final String address = String.format("%s/%d", actualHost, port);

        switch (actualProtocol) {
            case "udp":
                return new UdpAddress(address);
            case "tcp":
                return new TcpAddress(address);
            case "tls":
                return new TlsAddress(address);
            default:
                throw new SnmpClientException(String.format("invalid transport protocol specified '%s', expecting 'udp', 'tcp' or 'tls'", protocol));
        }
    }

    private static AbstractTransportMapping<? extends Address> createTransport(Address address) throws IOException {
        if (address instanceof TlsAddress) {
            return new TLSTM();
        }

        if (address instanceof TcpAddress) {
            return new DefaultTcpTransportMapping((TcpAddress) address);
        }

        return new DefaultUdpTransportMapping((UdpAddress) address);
    }

    @Override
    public void close() {
        try {
            snmp.close();
        } catch (Exception e) {
            logger.error("Error closing SNMP client", e);
        }
    }

    final Snmp getSnmp() {
        return snmp;
    }
}