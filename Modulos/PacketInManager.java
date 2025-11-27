package net.floodlightcontroller.packetinmanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.IPv4Address;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;

import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.DHCPOption;

public class PacketInManager implements IOFMessageListener, IFloodlightModule {

    protected IFloodlightProviderService floodlightProvider;

    // DHCP Message Types (RFC 2131)
    private static final byte DHCPDISCOVER = 1;
    private static final byte DHCPOFFER    = 2;
    private static final byte DHCPREQUEST  = 3;
    private static final byte DHCPACK      = 5;

    // Solo para referencia / logs (no se usa para el forwarding)
    private static final String DHCP_SERVER_IP = "10.0.0.6";

    // IP del portal cautivo
    private static final IPv4Address PORTAL_IP = IPv4Address.of("10.0.0.7");

    // Conjunto de IPs de clientes que ya recibieron DHCPACK (autorizados a ir al portal)
    private final Set<IPv4Address> allowedClients =
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

        // --- ARP ---
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

        // --- IPv4 ---
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
                                allowedClients.add(clientIp);
                                System.out.println("    Cliente AUTORIZADO para portal: " +
                                                   clientIp + " → " + PORTAL_IP);
                            }
                        }

                        // En cualquier mensaje DHCP (DISCOVER, OFFER, REQUEST, ACK, etc.)
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

            // ===========================================
            // 2) Tráfico permitido: cliente ↔ portal
            // ===========================================
            // (a) Cliente autorizado → Portal
            if (allowedClients.contains(srcIp) && dstIp.equals(PORTAL_IP)) {
                System.out.println(">>> Tráfico permitido CLIENTE (" + srcIp +
                                   ") → PORTAL (" + PORTAL_IP + ") <<<");
                forwardPortalTraffic(sw, pi, inPort, true);
                System.out.println("====================================\n");
                return Command.STOP;
            }

            // (b) Portal → Cliente autorizado
            if (srcIp.equals(PORTAL_IP) && allowedClients.contains(dstIp)) {
                System.out.println(">>> Tráfico permitido PORTAL (" + PORTAL_IP +
                                   ") → CLIENTE (" + dstIp + ") <<<");
                forwardPortalTraffic(sw, pi, inPort, false);
                System.out.println("====================================\n");
                return Command.STOP;
            }

            // Cualquier otro tráfico IPv4 que no sea DHCP ni portal queda bloqueado
        }

        System.out.println("====================================\n");

        // No hacemos nada especial → el paquete se descarta (sin Forwarding module)
        return Command.CONTINUE;
    }

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

    /**
     * Reenvía tráfico entre clientes autorizados y el portal cautivo.
     * Usamos también FLOOD para no depender de servicios de routing/topología.
     */
    private void forwardPortalTraffic(IOFSwitch sw,
                                      OFPacketIn pi,
                                      OFPort inPort,
                                      boolean clientToPortal) {

        System.out.println(">>> Enviando PacketOut (OFPP_FLOOD) para tráfico CLIENTE↔PORTAL <<<");
        System.out.println("    Sentido: " + (clientToPortal ? "CLIENTE → PORTAL" : "PORTAL → CLIENTE"));

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
        return deps;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        this.floodlightProvider =
                context.getServiceImpl(IFloodlightProviderService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context)
            throws FloodlightModuleException {

        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        System.out.println("=== PacketInManager cargado (DHCP + ARP + portal cautivo " +
                           PORTAL_IP + ") ===");
    }
}
