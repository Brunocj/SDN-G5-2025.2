package net.floodlightcontroller.packetinmanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.restserver.RestletRoutable;


import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.types.OFBufferId;

import org.projectfloodlight.openflow.protocol.action.OFAction;

import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.U64;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

import net.floodlightcontroller.core.internal.IOFSwitchService;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.DHCPOption;

import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;

public class PacketInManager implements IOFMessageListener, IFloodlightModule {

    protected IFloodlightProviderService floodlightProvider;
    protected IOFSwitchService           switchService;
    protected IRoutingService            routingService;
    protected IRestApiService restApiService;


    // =========================================================
    //            CONFIGURACIÓN GENERAL / CONSTANTES
    // =========================================================

    // DHCP Message Types (RFC 2131)
    private static final byte DHCPDISCOVER = 1;
    private static final byte DHCPOFFER    = 2;
    private static final byte DHCPREQUEST  = 3;
    private static final byte DHCPACK      = 5;

    // Solo para referencia / logs (no se usa para el forwarding)
    private static final String DHCP_SERVER_IP = "10.0.0.6";

    // =========================================================
    //          CONFIGURACIÓN DEL PORTAL CAUTIVO (EDITAR AQUÍ)
    // =========================================================
    //
    // Cambia estos 3 valores según tu topología:
    //
    //  - PORTAL_IP: IP del servidor del portal cautivo
    //  - PORTAL_SWITCH: DPID del switch donde está conectado el portal
    //  - PORTAL_PORT: puerto del switch donde está conectado el portal

    private static final IPv4Address PORTAL_IP =
            IPv4Address.of("10.0.0.7");

    private static final DatapathId PORTAL_SWITCH =
            DatapathId.of("00:00:92:f8:bd:cb:50:49"); // DPID del switch del portal

    private static final OFPort PORTAL_PORT =
            OFPort.of(4); // Puerto del portal en ese switch

    // Parámetros de los flujos que se instalarán para host ↔ portal
    private static final int FLOW_PRIORITY      = 100;
    private static final int FLOW_IDLE_TIMEOUT  = 300; // segundos 
    private static final int FLOW_HARD_TIMEOUT  = 0;   // 0 = sin límite

    // =========================================================
    //               ESTRUCTURAS DE ESTADO POR HOST
    // =========================================================

    private enum HostState {
        UNKNOWN,            // Lo he visto muy poco (ARP/DHCP)
        SEEN_WITH_IP,       // Sé que tiene IP (por DHCP o IPv4)
        CAPTURED_TO_PORTAL, // Flujos instalados solo host ↔ portal
        FULL_ACCESS         // Reservado para futura integración (ya autenticado)
    }

    private static class ClientSession {
        IPv4Address ip;
        MacAddress  mac;
        DatapathId  switchId;
        OFPort      port;
        HostState   state;
        long        lastSeenMillis;
    }

    // Tabla de sesiones básicas por IP
    private final Map<IPv4Address, ClientSession> clientSessions =
            new ConcurrentHashMap<IPv4Address, ClientSession>();

    // Hosts que están encaminados al portal (pendientes de autenticación de aplicación)
    private final Set<IPv4Address> portalPendingAuth =
            Collections.newSetFromMap(new ConcurrentHashMap<IPv4Address, Boolean>());

    @Override
    public String getName() {
        return PacketInManager.class.getSimpleName();
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return false;
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

        if (msg.getType() != OFType.PACKET_IN) {
            return Command.CONTINUE;
        }

        OFPacketIn pi = (OFPacketIn) msg;

        Ethernet eth = IFloodlightProviderService.bcStore.get(
                cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

        EthType etherType = eth.getEtherType();

        // No tocar LLDP
        if (etherType == EthType.LLDP) {
            return Command.CONTINUE;
        }

        System.out.println("====================================");
        System.out.println(" PACKET-IN INTERCEPTADO POR PacketInManager");
        System.out.println("====================================");
        System.out.println("➡ Switch DPID : " + sw.getId());
        System.out.println("➡ BufferId    : " + pi.getBufferId());

        OFPort inPort = pi.getMatch().get(MatchField.IN_PORT);
        System.out.println("➡ Puerto IN   : " + inPort);

        System.out.println("➡ EtherType   : " + etherType);
        System.out.println("➡ MAC Origen  : " + eth.getSourceMACAddress());
        System.out.println("➡ MAC Destino : " + eth.getDestinationMACAddress());
        System.out.println("➡ Tamaño      : " + pi.getData().length + " bytes");

        // =====================================================
        //                     TRATAMIENTO ARP
        // =====================================================
        if (etherType == EthType.ARP) {
            ARP arp = (ARP) eth.getPayload();
            System.out.println("--- ARP INFO ---");
            System.out.println("ARP Sender IP : " + arp.getSenderProtocolAddress());
            System.out.println("ARP Target IP : " + arp.getTargetProtocolAddress());

            // Importante: permitir que ARP circule (DHCP, gateway, portal, etc.)
            floodArpPacket(sw, pi, inPort);

            System.out.println("====================================\n");
            return Command.STOP;
        }

        // =====================================================
        //                     TRATAMIENTO IPv4
        // =====================================================
        if (etherType == EthType.IPv4) {
            IPv4 ipv4 = (IPv4) eth.getPayload();
            IPv4Address srcIp = ipv4.getSourceAddress();
            IPv4Address dstIp = ipv4.getDestinationAddress();

            System.out.println("--- IPv4 INFO ---");
            System.out.println("SRC IP        : " + srcIp);
            System.out.println("DST IP        : " + dstIp);
            System.out.println("L4 Protocol   : " + ipv4.getProtocol());

            // =========================
            // 1) Gestión de DHCP (UDP)
            // =========================
            if (ipv4.getProtocol() == IpProtocol.UDP) {
                UDP udp = (UDP) ipv4.getPayload();

                TransportPort srcPort = udp.getSourcePort();
                TransportPort dstPort = udp.getDestinationPort();

                System.out.println("UDP SRC PORT  : " + srcPort.getPort());
                System.out.println("UDP DST PORT  : " + dstPort.getPort());

                // Cliente → Servidor DHCP (68 -> 67)
                boolean isDhcpClientToServer =
                        srcPort.equals(UDP.DHCP_CLIENT_PORT) &&
                        dstPort.equals(UDP.DHCP_SERVER_PORT);

                // Servidor → Cliente DHCP (67 -> 68)
                boolean isDhcpServerToClient =
                        srcPort.equals(UDP.DHCP_SERVER_PORT) &&
                        dstPort.equals(UDP.DHCP_CLIENT_PORT);

                if (isDhcpClientToServer || isDhcpServerToClient) {
                    System.out.println("Posible tráfico DHCP detectado");

                    if (udp.getPayload() instanceof DHCP) {
                        DHCP dhcp = (DHCP) udp.getPayload();

                        byte msgTypeVal = -1;
                        for (DHCPOption opt : dhcp.getOptions()) {
                            if (opt.getCode() ==
                                DHCP.DHCPOptionCode.OptionCode_MessageType.getValue()) {
                                msgTypeVal = opt.getData()[0];
                                break;
                            }
                        }

                        System.out.println("DHCP Message Type (opción 53) = " + msgTypeVal);

                        if (msgTypeVal == DHCPDISCOVER) {
                            System.out.println(">>> DHCP DISCOVER DETECTADO (HOST → SERVER) <<<");
                            System.out.println("    From MAC : " + eth.getSourceMACAddress());
                            System.out.println("    On switch: " + sw.getId() +
                                               "  IN_PORT: " + inPort);
                        } else if (msgTypeVal == DHCPACK) {
                            // Aquí el servidor está confirmando la IP al cliente
                            IPv4Address clientIp = dhcp.getYourIPAddress();
                            System.out.println(">>> DHCP ACK DETECTADO <<<");
                            System.out.println("    IP asignada al cliente: " + clientIp);

                            if (clientIp != null &&
                                !clientIp.equals(IPv4Address.NONE)) {

                                long now = System.currentTimeMillis();
                                ClientSession s = clientSessions.get(clientIp);
                                if (s == null) {
                                    s = new ClientSession();
                                    s.ip = clientIp;
                                    s.state = HostState.SEEN_WITH_IP;
                                    s.lastSeenMillis = now;
                                    clientSessions.put(clientIp, s);
                                } else {
                                    s.ip = clientIp;
                                    if (s.state == null || s.state == HostState.UNKNOWN) {
                                        s.state = HostState.SEEN_WITH_IP;
                                    }
                                    s.lastSeenMillis = now;
                                }

                                System.out.println("    Cliente con IP por DHCP registrado en sesiones: "
                                                   + clientIp);
                            }
                        }

                        // En cualquier mensaje DHCP (DISCOVER, OFFER, REQUEST, ACK, etc.),
                        // reenviamos la trama usando OFPP_FLOOD para que llegue a todos los switches.
                        floodDhcpPacket(sw, pi, inPort, isDhcpClientToServer, isDhcpServerToClient);

                        System.out.println("====================================\n");
                        return Command.STOP;

                    } else {
                        System.out.println("UDP payload NO es instancia de DHCP: "
                                + udp.getPayload().getClass().getName());
                    }
                }
            }

            // =====================================================
            // 2) Lógica de captura al portal cautivo mediante FLOWS
            // =====================================================

            // Ignoramos como "trigger" el tráfico originado por el propio portal
            if (srcIp != null && srcIp.equals(PORTAL_IP)) {
                System.out.println("Tráfico originado desde el portal, no se usa como trigger.");
                System.out.println("====================================\n");
                return Command.CONTINUE;
            }

            // No tiene mucho sentido procesar IP NONE
            if (srcIp == null || srcIp.equals(IPv4Address.NONE)) {
                System.out.println("SRC IP inválida, ignorando.");
                System.out.println("====================================\n");
                return Command.CONTINUE;
            }

            long now = System.currentTimeMillis();

            // Obtenemos o creamos la sesión del host
            ClientSession session = clientSessions.get(srcIp);
            if (session == null) {
                session = new ClientSession();
                session.ip       = srcIp;
                session.mac      = eth.getSourceMACAddress();
                session.switchId = sw.getId();
                session.port     = inPort;
                session.state    = HostState.SEEN_WITH_IP; // Lo vemos ya con IPv4
                session.lastSeenMillis = now;
                clientSessions.put(srcIp, session);
                System.out.println("Nueva sesión creada para host " + srcIp +
                                   " en switch " + session.switchId +
                                   " / puerto " + session.port);
            } else {
                // Actualizamos info dinámica
                session.mac      = eth.getSourceMACAddress();
                session.switchId = sw.getId();
                session.port     = inPort;
                session.lastSeenMillis = now;

                if (session.state == null || session.state == HostState.UNKNOWN) {
                    session.state = HostState.SEEN_WITH_IP;
                }
            }

            // ==========================
            // REINSTALACIÓN DE FLOWS
            // ==========================

            // Si el host ya tiene FULL_ACCESS, no aplicamos portal cautivo
            if (session.state == HostState.FULL_ACCESS) {
                System.out.println("Host " + srcIp + " tiene FULL_ACCESS → no se reinstalan flujos.");
                System.out.println("====================================\n");
                return Command.CONTINUE;
            }

            // Caso especial: El host está capturado pero sus flows expiraron
            // Si vuelve a hablar con el portal, reinstalamos los flows
            if (session.state == HostState.CAPTURED_TO_PORTAL &&
                dstIp != null &&
                dstIp.equals(PORTAL_IP)) {

                System.out.println("Host " + srcIp + " ya tenía estado CAPTURED_TO_PORTAL → reinstalando flows.");
                captureHostToPortal(session);
                System.out.println("====================================\n");

                // Se detiene para no seguir procesando el paquete
                return Command.STOP;
            }
            

            // Condición de captura:
            //  - Host con IP válida y estado SEEN_WITH_IP
            //  - Tráfico hacia cualquier destino que NO sea el portal (ej: quiere ir a internet)
            if (session.state == HostState.SEEN_WITH_IP &&
                dstIp != null && !dstIp.equals(PORTAL_IP)) {

                System.out.println(">>> Trigger de captura detectado para host " + srcIp + " <<<");
                System.out.println("    Quiere ir a: " + dstIp + " → lo redirigiremos al portal " + PORTAL_IP);

                captureHostToPortal(session);

                System.out.println("====================================\n");
                // Este paquete en particular se puede descartar;
                // el host reenviará y ya encontrará los flujos instalados.
                return Command.STOP;
            }

            // Si el tráfico ya va al portal (dstIp == PORTAL_IP) y aún no tiene flows,
            // también lo capturamos aquí.
            if (session.state == HostState.SEEN_WITH_IP &&
                dstIp != null && dstIp.equals(PORTAL_IP)) {

                System.out.println(">>> Host " + srcIp + " ya intenta hablar con el portal " +
                                   PORTAL_IP + ", instalando flujos host ↔ portal <<<");

                captureHostToPortal(session);

                System.out.println("====================================\n");
                return Command.STOP;
            }

            // Cualquier otro tráfico IPv4 que no entre en los casos anteriores queda bloqueado
        }

        System.out.println("====================================\n");

        // No hacemos nada especial → el paquete se descarta (sin Forwarding module)
        return Command.CONTINUE;
    }

    // =========================================================
    //       Captura host → portal: instalación de FLOWS
    //       (USANDO RUTA MULTI-SWITCH CON IRoutingService)
    // =========================================================

    private void captureHostToPortal(ClientSession s) {
        if (s == null || s.ip == null || s.switchId == null || s.port == null) {
            System.out.println("No se puede capturar host: información incompleta de sesión.");
            return;
        }

        DatapathId srcSw  = s.switchId;
        OFPort     srcPort = s.port;
        DatapathId dstSw  = PORTAL_SWITCH;
        OFPort     dstPort = PORTAL_PORT;

        System.out.println("Obteniendo ruta desde host " + s.ip +
                           " (" + srcSw + "/" + srcPort + ") hasta portal " +
                           PORTAL_IP + " (" + dstSw + "/" + dstPort + ")");

        Route route = routingService.getRoute(srcSw, srcPort, dstSw, dstPort, U64.ZERO);
        if (route == null) {
            System.out.println("No se pudo obtener ruta host → portal. No se instalan flujos.");
            return;
        }

        List<NodePortTuple> path = route.getPath();
        if (path == null || path.isEmpty()) {
            System.out.println("Ruta vacía host → portal. No se instalan flujos.");
            return;
        }

        System.out.println("Ruta obtenida host " + s.ip + " → portal: " + path);

        installBidirectionalPortalFlowsAlongPath(path, s.ip);

        s.state = HostState.CAPTURED_TO_PORTAL;
        s.lastSeenMillis = System.currentTimeMillis();
        portalPendingAuth.add(s.ip);

        System.out.println("Host " + s.ip + " capturado hacia portal " + PORTAL_IP +
                           " (estado = CAPTURED_TO_PORTAL).");
    }

    /**
     * Instala flujos host ↔ portal en TODOS los switches de la ruta.
     * La ruta es una lista de NodePortTuple con pares (inPort, outPort) por switch.
     */
    private void installBidirectionalPortalFlowsAlongPath(List<NodePortTuple> path,
                                                          IPv4Address clientIp) {

        if (path.size() < 2) {
            System.out.println("Ruta demasiado corta para instalar flujos.");
            return;
        }

        int numHops = path.size() / 2;
        System.out.println("Instalando flows CLIENTE↔PORTAL a lo largo de la ruta. Hops: " + numHops);

        // ------------ SENTIDO CLIENTE → PORTAL ------------
        for (int i = 0; i < path.size() - 1; i += 2) {
            NodePortTuple nptIn  = path.get(i);     // (switch, puerto ingreso)
            NodePortTuple nptOut = path.get(i + 1); // (mismo switch, puerto salida al siguiente)

            DatapathId swId  = nptIn.getNodeId();
            OFPort outPort   = nptOut.getPortId();

            IOFSwitch sw = switchService.getSwitch(swId);
            if (sw == null) {
                System.out.println("No se puede obtener switch " + swId + " para CLIENTE→PORTAL.");
                continue;
            }

            installPortalFlow(sw, clientIp, outPort, true);
        }

        // ------------ SENTIDO PORTAL → CLIENTE ------------
        for (int i = path.size() - 1; i > 0; i -= 2) {
            NodePortTuple nptIn  = path.get(i);     // (switch, puerto ingreso en este sentido)
            NodePortTuple nptOut = path.get(i - 1); // (mismo switch, puerto salida hacia hop anterior)

            DatapathId swId  = nptIn.getNodeId();
            OFPort outPort   = nptOut.getPortId();

            IOFSwitch sw = switchService.getSwitch(swId);
            if (sw == null) {
                System.out.println("No se puede obtener switch " + swId + " para PORTAL→CLIENTE.");
                continue;
            }

            installPortalFlow(sw, clientIp, outPort, false);
        }
    }

    /**
     * Instala un flujo unidireccional en el switch:
     *  - clientToPortal = true  → match: SRC=clientIp, DST=PORTAL_IP, out=egressPort
     *  - clientToPortal = false → match: SRC=PORTAL_IP, DST=clientIp, out=egressPort
     */
    private void installPortalFlow(IOFSwitch sw,
                                   IPv4Address clientIp,
                                   OFPort egressPort,
                                   boolean clientToPortal) {

        System.out.println("Creando FLOW " +
                (clientToPortal ? "CLIENTE→PORTAL" : "PORTAL→CLIENTE") +
                " para host " + clientIp + " en switch " + sw.getId() +
                " (outPort=" + egressPort + ")");

        // Match de L3 (no filtramos por puerto de entrada)
        Match match;
        if (clientToPortal) {
            match = sw.getOFFactory().buildMatch()
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IPV4_SRC, clientIp)
                    .setExact(MatchField.IPV4_DST, PORTAL_IP)
                    .build();
        } else {
            match = sw.getOFFactory().buildMatch()
                    .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                    .setExact(MatchField.IPV4_SRC, PORTAL_IP)
                    .setExact(MatchField.IPV4_DST, clientIp)
                    .build();
        }

        // Acción: enviar al puerto correcto
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(sw.getOFFactory().actions().output(egressPort, 0xFFFF));

        // Instrucción Apply-Actions
        OFInstruction applyActions =
                sw.getOFFactory().instructions().applyActions(actions);

        List<OFInstruction> instructions = new ArrayList<OFInstruction>();
        instructions.add(applyActions);

        OFFlowAdd flowAdd = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setHardTimeout(FLOW_HARD_TIMEOUT)
                .setIdleTimeout(FLOW_IDLE_TIMEOUT)
                .setPriority(FLOW_PRIORITY)
                .setMatch(match)
                .setInstructions(instructions)
                .build();

        sw.write(flowAdd);

        System.out.println("FLOW instalado en switch " + sw.getId() +
                           " (outPort=" + egressPort + ").");
    }

    // =========================================================
    //                        DHCP y ARP
    // =========================================================

    /**
     * Reenvía el paquete DHCP a todos los puertos del switch usando OFPP_FLOOD.
     * Como todos los switches tienen regla table-miss hacia el controlador,
     * esto hace que el paquete vaya saltando switch por switch hasta llegar
     * al servidor DHCP y luego de vuelta al cliente.
     */
    private void floodDhcpPacket(IOFSwitch sw,
                                 OFPacketIn pi,
                                 OFPort inPort,
                                 boolean isClientToServer,
                                 boolean isServerToClient) {

        System.out.println(">>> Enviando PacketOut (OFPP_FLOOD) para tráfico DHCP <<<");
        if (isClientToServer) {
            System.out.println("    Dirección: CLIENTE → SERVIDOR (68→67)");
        } else if (isServerToClient) {
            System.out.println("    Dirección: SERVIDOR → CLIENTE (67→68)");
        }

        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();

        pob.setBufferId(pi.getBufferId());
        pob.setInPort(inPort);

        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(sw.getOFFactory().actions().output(OFPort.FLOOD, 0xFFFF));
        pob.setActions(actions);

        pob.setData(pi.getData());

        sw.write(pob.build());
    }

    /**
     * FLOOD para paquetes ARP (necesario para que DHCP, gateway, portal, etc. funcionen).
     */
    private void floodArpPacket(IOFSwitch sw,
                                OFPacketIn pi,
                                OFPort inPort) {

        System.out.println(">>> Enviando PacketOut (OFPP_FLOOD) para ARP <<<");

        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        pob.setBufferId(pi.getBufferId());
        pob.setInPort(inPort);

        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(sw.getOFFactory().actions().output(OFPort.FLOOD, 0xFFFF));
        pob.setActions(actions);

        pob.setData(pi.getData());

        sw.write(pob.build());
    }

    // ===== Métodos de IFloodlightModule =====

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        List<Class<? extends IFloodlightService>> deps =
                new ArrayList<Class<? extends IFloodlightService>>();
        deps.add(IFloodlightProviderService.class);
        deps.add(IOFSwitchService.class);
        deps.add(IRoutingService.class);
        deps.add(IRestApiService.class);   // 👈 NUEVO
        return deps;
    }


    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService      = context.getServiceImpl(IOFSwitchService.class);
        routingService     = context.getServiceImpl(IRoutingService.class);
        restApiService     = context.getServiceImpl(IRestApiService.class); // 👈 NUEVO
    }


    @Override
    public void startUp(FloodlightModuleContext context)
            throws FloodlightModuleException {

        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        System.out.println("=== PacketInManager cargado ===");
        System.out.println("    - Manejo de DHCP + ARP");
        System.out.println("    - Captura a portal cautivo");

        // 👇 Aquí enganchas tus endpoints
        restApiService.addRestletRoutable(new SecurityWebRoutable());
    }


}
