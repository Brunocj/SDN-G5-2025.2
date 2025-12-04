package net.floodlightcontroller.packetinmanager;

import java.util.HashMap;

import org.projectfloodlight.openflow.types.VlanVid;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.TableId;

import net.floodlightcontroller.packetinmanager.web.AccessControlWebRoutable;
import net.floodlightcontroller.packetinmanager.web.AllowedResource;

import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;

import java.util.Iterator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;

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
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
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

import net.floodlightcontroller.core.IOFSwitchListener;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import net.floodlightcontroller.core.PortChangeType;

public class PacketInManager implements IOFMessageListener, IFloodlightModule, IAccessControlService, IOFSwitchListener {

    protected IFloodlightProviderService floodlightProvider;
    protected IOFSwitchService           switchService;
    protected IRoutingService            routingService;
    protected IRestApiService restApiService;
    protected IDeviceService             deviceManagerService;


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
    
    // Identificador numérico para "Forwarding-Flow"
    // Usamos 0xAC1 (Parece "ACL")
    private static final long ACL_APP_ID = 0xAC1L;

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
    // Flujos de bloqueo por spoofing (DROP en el puerto, tabla 0)
    // TTL FIJO: 30 min vía HARD_TIMEOUT (no por idle)
    private static final int SPOOFING_DROP_PRIORITY     = 65000; // muy alta
    private static final int SPOOFING_DROP_IDLE_TIMEOUT = 0;     // no expira por inactividad
    private static final int SPOOFING_DROP_HARD_TIMEOUT = 1800;  // 30 min = 30*60

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

    private static final Set<IPv4Address> IGNORED_IPS = new HashSet<>();

    static {
        // Servidor DHCP (para que sus PACKET_IN normales no pasen por la lógica de spoofing/portal)
        IGNORED_IPS.add(IPv4Address.of(DHCP_SERVER_IP));

        IGNORED_IPS.add(IPv4Address.of("20.0.0.3"));
        IGNORED_IPS.add(IPv4Address.of("20.0.0.4"));
        IGNORED_IPS.add(IPv4Address.of("20.0.0.5"));
    }

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
            // Necesarios para SecurityHelper.checkIpMacSwitchSpoofing(...)
            String ipStr      = srcIp.toString();
            String macStr     = eth.getSourceMACAddress().toString();
            String switchStr  = sw.getId().toString();
            String inportStr  = inPort.toString();

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

                            // MAC de origen
                            MacAddress srcMac = eth.getSourceMACAddress();
                            System.out.println("    From MAC : " + srcMac);
                            System.out.println("    On switch: " + sw.getId() +
                                            "  IN_PORT: " + inPort);

                            // ======== Chequeo de MAC spoofing =========
                            // SecurityHelper se encarga de la lógica interna
                            boolean esSpoofing = SecurityHelper.checkMacSpoofing(srcMac.toString());

                            if (esSpoofing) {
                                System.out.println("[Security] ⚠ POSIBLE MAC SPOOFING detectado para MAC " + srcMac);
                                System.out.println("           → BLOQUEANDO puerto " + inPort + " en switch " + sw.getId());

                                // Bloquear TODO el tráfico que entre por este puerto
                                installSpoofingDropFlow(sw, inPort, "DHCP MAC spoofing");

                                System.out.println("    No se reenvía este DHCP DISCOVER por spoofing.");
                                System.out.println("====================================\n");
                                return Command.STOP;  // cortamos aquí, NO se hace floodDhcpPacket
                            } else {
                                System.out.println("[Security] MAC " + srcMac +
                                                " no presenta spoofing según sesiones_activas.");
                            }
                        }   else if (msgTypeVal == DHCPACK) {
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

            // ==============================================
            // 1.5) Lista de IPs a ignorar en análisis L3/L4
            //      (NO afecta al tratamiento DHCP de arriba)
            // ==============================================
            if (srcIp != null && IGNORED_IPS.contains(srcIp)) {
                System.out.println("[PacketInManager] SRC IP " + srcIp +
                                   " está en IGNORED_IPS → se ignora análisis de spoofing/portal.");
                System.out.println("====================================\n");
                return Command.CONTINUE;
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
                session.state    = HostState.SEEN_WITH_IP;
                session.lastSeenMillis = now;
                clientSessions.put(srcIp, session);

                System.out.println("Nueva sesión creada para host " + srcIp +
                                " en switch " + session.switchId +
                                " / puerto " + session.port);

                // ⭐ CREAR POSIBLE SESIÓN EN BD SOLO UNA VEZ
                SessionHelper.crearEntrada(
                    srcIp.toString(),
                    eth.getSourceMACAddress().toString(),
                    sw.getId().toString(),
                    inPort.toString()
                );

            } else {
                session.mac      = eth.getSourceMACAddress();
                session.switchId = sw.getId();
                session.port     = inPort;
                session.lastSeenMillis = now;
                // ⭐ CREAR POSIBLE SESIÓN EN BD SOLO UNA VEZ
                SessionHelper.crearEntrada(
                    srcIp.toString(),
                    eth.getSourceMACAddress().toString(),
                    sw.getId().toString(),
                    inPort.toString()
                );
            }

            // ==============================================
            // 2.1) Chequeo de SPOOFING usando nueva función (1/2/3)
            //     PERO: si el destino es el portal, NO se analiza spoofing
            // ==============================================
            if (dstIp != null && dstIp.equals(PORTAL_IP)) {
                System.out.println("[Security] DST = PORTAL → no se analiza spoofing.");
            } else {
                int verificacion = SecurityHelper.verificarSesion(
                        ipStr, macStr, switchStr, inportStr);

                switch (verificacion) {

                    case 1: // SPOOFING
                        System.out.println("[Security] ⚠ SPOOFING detectado para host " + srcIp);
                        installSpoofingDropFlow(sw, inPort, "SPOOFING detectado");
                        System.out.println("====================================\n");
                        return Command.STOP;

                    case 2: // OK → sesión válida, permitir instalación/reinstalación de flows
                        System.out.println("[Security] El usuario está solicitando flows");
                        break;

                    case 3: // ERROR USUARIO → no registrado → enviarlo a portal
                        System.out.println("[Security] Usuario NO registrado (Caso 3).");
                        // No se bloquea, simplemente continúa para ser capturado por el portal
                        break;

                    default:
                        System.out.println("[Security] Error inesperado en verificarSesion().");
                        break;
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
        Collection<Class<? extends IFloodlightService>> l = new ArrayList<>();
        l.add(IAccessControlService.class); // <--- AGREGAR
        return l;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<>();
        m.put(IAccessControlService.class, this); // <--- AGREGAR
        return m;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        List<Class<? extends IFloodlightService>> deps =
                new ArrayList<Class<? extends IFloodlightService>>();
        deps.add(IFloodlightProviderService.class);
        deps.add(IOFSwitchService.class);
        deps.add(IRoutingService.class);
        deps.add(IRestApiService.class);   // 👈 NUEVO
        deps.add(IDeviceService.class);    // 👈 NUEVO
        return deps;
    }


    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        switchService      = context.getServiceImpl(IOFSwitchService.class);
        routingService     = context.getServiceImpl(IRoutingService.class);
        restApiService     = context.getServiceImpl(IRestApiService.class); // 👈 NUEVO
        deviceManagerService = context.getServiceImpl(IDeviceService.class);// 👈 NUEVO
    }


    @Override
    public void startUp(FloodlightModuleContext context)
            throws FloodlightModuleException {

        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        switchService.addOFSwitchListener(this);
        System.out.println("=== PacketInManager cargado ===");
        System.out.println("    - Manejo de DHCP + ARP");
        System.out.println("    - Captura a portal cautivo");

        // 👇 Aquí enganchas tus endpoints
        restApiService.addRestletRoutable(new SecurityWebRoutable());
        restApiService.addRestletRoutable(new AccessControlWebRoutable());
    }
    
    
    // =========================================================
    //        IMPLEMENTACIÓN DE IAccessControlService
    // =========================================================

    @Override
    public void allowAccessToResources(IPv4Address userIp, List<AllowedResource> resources) {
        System.out.println(">>> [ACL] Iniciando cálculo de rutas para usuario: " + userIp);

        // 1. Localizar al Usuario (Source)
        ClientSession session = clientSessions.get(userIp);
        if (session == null) {
            System.err.println("ERROR: Usuario " + userIp + " no encontrado en sesiones locales.");
            return;
        }
        
        MacAddress userMac = session.mac;
        DatapathId srcSwitch = session.switchId;
        OFPort srcPort = session.port;

        // 2. Procesar cada recurso
        for (AllowedResource res : resources) {
            try {
                IPv4Address dstIp = IPv4Address.of(res.getIp());
                
                // 3. Localizar al Servidor/Recurso (Destination) usando DeviceManager
                // (Esto es lo que hace Forwarding.java para saber dónde está el destino)
                Iterator<? extends IDevice> devices = deviceManagerService.queryDevices(
                        MacAddress.NONE, VlanVid.ZERO, dstIp, IPv6Address.NONE, DatapathId.NONE, OFPort.ZERO);

                if (!devices.hasNext()) {
                    System.out.println("WARN: Recurso " + dstIp + " no encontrado en la topología (DeviceManager).");
                    continue; 
                }

                IDevice dstDevice = devices.next();
                SwitchPort[] aps = dstDevice.getAttachmentPoints();
                if (aps.length == 0) continue;

                SwitchPort dstAp = aps[0]; // Tomamos su punto de conexión principal
                DatapathId dstSwitch = dstAp.getSwitchDPID();
                OFPort dstPort = dstAp.getPort();

                System.out.println("   -> Ruta ACL: Usuario(" + srcSwitch + ") <--> Recurso(" + dstSwitch + ")");

                // 4. Calcular Rutas (Ida y Vuelta)
                Route routeOut = routingService.getRoute(srcSwitch, srcPort, dstSwitch, dstPort, U64.ZERO);
                Route routeIn  = routingService.getRoute(dstSwitch, dstPort, srcSwitch, srcPort, U64.ZERO);

                if (routeOut != null && routeIn != null) {
                    // Instalar IDA (Usuario -> Servidor)
                    installAclFlows(routeOut, userIp, dstIp, res.getProtocol(), res.getPort(), true, userMac, userIp);
                    
                    // Instalar VUELTA (Servidor -> Usuario)
                    installAclFlows(routeIn, dstIp, userIp, res.getProtocol(), res.getPort(), false, userMac, userIp);
                } else {
                    System.err.println("ERROR: No hay ruta física entre " + srcSwitch + " y " + dstSwitch);
                }

            } catch (Exception e) {
                System.err.println("Error procesando recurso " + res.getIp() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Helper para instalar flujos a lo largo de una ruta (Basado en Forwarding.pushRoute)
     */
    private void installAclFlows(Route route, IPv4Address srcIp, IPv4Address dstIp, 
                                 String proto, int tcpPort, boolean isForwardDirection,
                                 MacAddress userMac, IPv4Address ownerIp) {
        
        List<NodePortTuple> path = route.getPath();
        System.out.println("   [DEBUG] Instalando flujos en " + (path.size()/2) + " switches para " + srcIp + " -> " + dstIp);
        
        // 1. GENERAR COOKIE COMPUESTO: [ APP_ID (32 bits) | IP_USUARIO (32 bits) ]
    
        // Obtenemos el valor numérico de la IP (asegurando que sea positivo con & 0xFFFFFFFFL)
        long ipValue = ownerIp.getInt() & 0xFFFFFFFFL;
        
        // Combinamos: Desplazamos el AppID 32 bits a la izquierda y sumamos la IP
        long rawCookie = (ACL_APP_ID << 32) | ipValue;
        
        U64 flowCookie = U64.of(rawCookie);
    
        System.out.println("   [DEBUG] Cookie Generado: " + Long.toHexString(rawCookie));
        
        // Iteramos switch por switch
        for (int i = 0; i < path.size() - 1; i += 2) {
            DatapathId dpid = path.get(i).getNodeId();
            OFPort outPort = path.get(i+1).getPortId();
            
            IOFSwitch sw = switchService.getSwitch(dpid);
            if (sw == null) {
                System.out.println("   [ERROR] Switch " + dpid + " no encontrado. Saltando.");
                continue;
            }

            Match.Builder mb = sw.getOFFactory().buildMatch();
            mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
              .setExact(MatchField.IPV4_SRC, srcIp)
              .setExact(MatchField.IPV4_DST, dstIp);
              

            // --- NUEVO: AGREGAR MAC ---
            if (userMac != null) {
                if (isForwardDirection) {
                    // IDA: El usuario es el origen
                    mb.setExact(MatchField.ETH_SRC, userMac);
                } else {
                    // VUELTA: El usuario es el destino
                    mb.setExact(MatchField.ETH_DST, userMac);
                }
            }
            
            
            // Filtros de Capa 4 (Puerto)
            if (proto != null && tcpPort > 0) {
                if (proto.equalsIgnoreCase("tcp")) {
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP);
                    // Truco: Si es ida, el destino es el puerto 80. Si es vuelta, el origen es 80.
                    if (isForwardDirection) {
                        mb.setExact(MatchField.TCP_DST, TransportPort.of(tcpPort));
                    } else {
                        mb.setExact(MatchField.TCP_SRC, TransportPort.of(tcpPort));
                    }
                } else if (proto.equalsIgnoreCase("udp")) {
                    mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP);
                    if (isForwardDirection) {
                        mb.setExact(MatchField.UDP_DST, TransportPort.of(tcpPort));
                    } else {
                        mb.setExact(MatchField.UDP_SRC, TransportPort.of(tcpPort));
                    }
                }
            }

            // Acción: Salida por el puerto calculado por el RoutingService
            List<OFAction> actions = new ArrayList<>();
            actions.add(sw.getOFFactory().actions().output(outPort, Integer.MAX_VALUE));

            OFFlowAdd flow = sw.getOFFactory().buildFlowAdd()
                .setMatch(mb.build())
                .setTableId(TableId.of(2))
                .setActions(actions)
                .setPriority(500)      // Prioridad mayor que el default
                .setIdleTimeout(3600)  // 1 hora de inactividad
                .setCookie(flowCookie) // <--- AQUÍ ASIGNAMOS EL NOMBRE NUMÉRICO
                .setHardTimeout(0)
                .build();

            sw.write(flow);
            
            // LOG DE CONFIRMACIÓN
            System.out.println("   [FLOW] Instalado en SW " + dpid + " | OutPort: " + outPort + " | Match: " + srcIp + "->" + dstIp + ":" + tcpPort);
        }
    }

    private void installSpoofingDropFlow(IOFSwitch sw, OFPort inPort, String reason) {
        if (sw == null || inPort == null) {
            System.out.println("[Security] No se puede instalar DROP: switch o puerto nulos.");
            return;
        }

        System.out.println("[Security] Instalando FLOW de DROP por spoofing en switch " +
                        sw.getId() + " puerto " + inPort + " (" + reason + ")");

        Match match = sw.getOFFactory().buildMatch()
                .setExact(MatchField.IN_PORT, inPort)  // TODO lo que entre por ese puerto
                .build();

        // Sin acciones = DROP
        List<OFInstruction> instructions = new ArrayList<>();

        OFFlowAdd dropFlow = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setTableId(TableId.of(0))                    // 👈 TABLA 0 explícita
                .setHardTimeout(SPOOFING_DROP_HARD_TIMEOUT)   // 👈 30 min fijos
                .setIdleTimeout(SPOOFING_DROP_IDLE_TIMEOUT)   // 👈 0 = no depende de idle
                .setPriority(SPOOFING_DROP_PRIORITY)
                .setMatch(match)
                .setInstructions(instructions)
                .build();

        sw.write(dropFlow);

        System.out.println("[Security] Flow de DROP por spoofing instalado en " +
                        sw.getId() + " / puerto " + inPort + " (tabla 0, TTL=30min).");
    }
    
    @Override
    public void revokeAccess(IPv4Address userIp) {
        System.out.println(">>> [ACL] Revocando acceso para usuario: " + userIp);
        
        // 1. Recalcular el mismo Cookie Compuesto
        long ipValue = userIp.getInt() & 0xFFFFFFFFL;
        long rawCookie = (ACL_APP_ID << 32) | ipValue;
        U64 flowCookie = U64.of(rawCookie);
        
        U64 cookieMask = U64.NO_MASK; // Coincidencia exacta

        // 2. Mandar orden de borrado a TODOS los switches
        // (Es más seguro borrar en todos por si el usuario se movió)
        for (DatapathId dpid : switchService.getAllSwitchDpids()) {
            IOFSwitch sw = switchService.getSwitch(dpid);
            if (sw == null) continue;

            OFFlowDelete flowDelete = sw.getOFFactory().buildFlowDelete()
                    .setTableId(TableId.of(2)) // Borrar de la Tabla 2 (donde pusimos las ACLs)
                    .setCookie(flowCookie)
                    .setCookieMask(cookieMask) // IMPORTANTE: Sin esto, borra todo
                    .build();
            
            sw.write(flowDelete);
            System.out.println("   [REVOKE] Enviado FlowDelete a SW " + dpid + " para cookie " + flowCookie);
        }

        // 3. Resetear estado de la sesión local
        ClientSession session = clientSessions.get(userIp);
        if (session != null) {
            session.state = HostState.SEEN_WITH_IP; // Lo devolvemos al estado inicial (sin acceso)
            // Opcional: session.state = HostState.CAPTURED_TO_PORTAL; si quieres forzar portal inmediato
        }
    }

    // =========================================================
    //        IMPLEMENTACIÓN DE IOFSwitchListener
    // =========================================================

    @Override
    public void switchAdded(DatapathId switchId) {
        // No hacemos nada aquí, esperamos a que se active
    }

    @Override
    public void switchRemoved(DatapathId switchId) {
    }

    @Override
    public void switchActivated(DatapathId switchId) {
        // ⭐ AQUÍ ES DONDE INSTALAMOS LA REGLA PERSISTENTE
        IOFSwitch sw = switchService.getSwitch(switchId);
        if (sw != null) {
            installDropMulticastFlow(sw);
        }
    }

    @Override
    public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
    }

    @Override
    public void switchChanged(DatapathId switchId) {
    }

    /**
     * Instala una regla en Tabla 0 para descartar tráfico Multicast (224.0.0.0/4)
     * Esto evita que el controlador se inunde de Packet-In de Avahi/mDNS.
     */
    private void installDropMulticastFlow(IOFSwitch sw) {
        System.out.println("[PacketInManager] Instalando regla DROP Multicast en Switch " + sw.getId());

        // Match: Todo lo que sea IPv4 destino 224.0.0.0 con máscara de red /4
        // Esto cubre desde 224.0.0.0 hasta 239.255.255.255
        Match match = sw.getOFFactory().buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setMasked(MatchField.IPV4_DST, IPv4Address.of("224.0.0.0"), IPv4Address.of("240.0.0.0"))
                .build();

        // Sin acciones = DROP
        List<OFInstruction> instructions = new ArrayList<>();

        OFFlowAdd dropFlow = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setTableId(TableId.of(0))
                .setPriority(1000) // Prioridad media-alta (más que el default, menos que ARP o seguridad crítica)
                .setHardTimeout(0) // Permanente
                .setIdleTimeout(0) // Permanente
                .setMatch(match)
                .setInstructions(instructions)
                .build();

        sw.write(dropFlow);

            // BLOQUEAR MULTICAST IPv6 (ff00::/8)
        Match match6 = sw.getOFFactory().buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv6)
                // IPv6 Multicast siempre empieza por ff00...
                .setMasked(MatchField.IPV6_DST, 
                        IPv6Address.of("ff00::"), 
                        IPv6Address.of("ff00::")) 
                .build();

        OFFlowAdd dropFlow6 = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setTableId(TableId.of(0))
                .setPriority(1000)
                .setHardTimeout(0)
                .setIdleTimeout(0)
                .setMatch(match6)
                .setInstructions(instructions) // Lista vacía = DROP
                .build();

        sw.write(dropFlow6);
    }

    /**
     * Bloquea una IP buscando su ubicación física en la BD si es necesario.
     * Método mejorado para defensa DDoS y R1.
     */
    /**
     * Bloquea al atacante. 
     * CORREGIDO: Busca el puerto en la BD porque el objeto en RAM no lo tiene.
     */
    public void blockIpAddress(IPv4Address targetIp) {
        System.out.println("[SECURITY] 🚨 Iniciando protocolo de bloqueo para IP: " + targetIp);

        IOFSwitch targetSwitch = null;
        OFPort targetPort = null;

        // PASO 1: Consultar la Base de Datos (posibles_sesiones)
        // Usamos esto PRIMERO porque aquí sí tenemos el puerto físico registrado.
        try {
            // [0]=ip, [1]=mac, [2]=switchid, [3]=inport
            String[] datosFisicos = SecurityHelper.obtenerParametrosDesdePosibles(targetIp.toString());

            if (datosFisicos != null && datosFisicos.length > 0) {
                // Parseamos Switch ID
                DatapathId dpid = DatapathId.of(datosFisicos[2]);
                targetSwitch = switchService.getSwitch(dpid);
                
                // Parseamos Puerto Físico
                int portNum = Integer.parseInt(datosFisicos[3]);
                targetPort = OFPort.of(portNum);
                
                System.out.println("[SECURITY] ✅ Ubicación física confirmada en BD: Switch " + dpid + " - Puerto " + portNum);
            }
        } catch (Exception e) {
            System.err.println("[SECURITY] Error consultando BD: " + e.getMessage());
        }

        // PASO 2: Si la BD falló, buscamos en Memoria (RAM) como respaldo
        if (targetSwitch == null) {
            ClientSession session = clientSessions.get(targetIp);
            if (session != null) {
                targetSwitch = switchService.getSwitch(session.switchId);
                // NOTA: No intentamos leer session.inPort para evitar el error de compilación
                System.out.println("[SECURITY] Ubicación parcial en RAM (Solo Switch, sin Puerto).");
            }
        }

        // PASO 3: Ejecutar el Castigo
        if (targetSwitch != null && targetPort != null) {
            // OPCIÓN A: Bloqueo de Puerto (Corte de Cable - La mejor opción)
            System.out.println("[SECURITY] ✂️ CORTANDO PUERTO " + targetPort + " en Switch " + targetSwitch.getId());
            installPortDropFlow(targetSwitch, targetPort);
            
        } else if (targetSwitch != null) {
            // OPCIÓN B: Bloqueo de IP en el Switch específico (Si no tenemos el puerto)
            System.out.println("[SECURITY] 🛡️ Puerto desconocido. Bloqueando IP origen en Switch " + targetSwitch.getId());
            installIpDropFlow(targetSwitch, targetIp);
            
        } else {
            // OPCIÓN C: Bloqueo Global (Pánico)
            System.out.println("[SECURITY] ⚠️ Ubicación desconocida. Aplicando BLOQUEO GLOBAL en todos los switches.");
            Map<DatapathId, IOFSwitch> allSwitches = switchService.getAllSwitchMap();
            for (IOFSwitch sw : allSwitches.values()) {
                installIpDropFlow(sw, targetIp);
            }
        }
    }

    /**
     * Instala una regla que descarta TODO lo que entra por un puerto específico.
     * Efecto: Es como desconectar el cable del usuario.
     */
    private void installPortDropFlow(IOFSwitch sw, OFPort port) {
        // Match: Todo lo que entre por IN_PORT
        Match match = sw.getOFFactory().buildMatch()
                .setExact(MatchField.IN_PORT, port) // <--- La clave del bloqueo físico
                .build();

        OFFlowAdd dropFlow = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setTableId(TableId.of(0))
                .setPriority(6000)      // Prioridad EXTREMA (Mayor que IP Drop 5000)
                .setHardTimeout(600)    // 10 minutos de castigo
                .setIdleTimeout(0)
                .setMatch(match)
                .setInstructions(new ArrayList<OFInstruction>()) // DROP
                .build();

        sw.write(dropFlow);
    }

    // Mantén tu método antiguo como respaldo (renómbralo a installIpDropFlow)
    private void installIpDropFlow(IOFSwitch sw, IPv4Address targetIp) {
        Match match = sw.getOFFactory().buildMatch()
                .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                .setExact(MatchField.IPV4_SRC, targetIp)
                .build();

        OFFlowAdd dropFlow = sw.getOFFactory().buildFlowAdd()
                .setBufferId(OFBufferId.NO_BUFFER)
                .setTableId(TableId.of(0))
                .setPriority(5000)
                .setHardTimeout(600)
                .setIdleTimeout(0)
                .setMatch(match)
                .setInstructions(new ArrayList<OFInstruction>())
                .build();

        sw.write(dropFlow);
    }
}
