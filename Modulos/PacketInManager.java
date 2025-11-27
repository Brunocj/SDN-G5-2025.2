package net.floodlightcontroller.packetinmanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;

import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.U64;

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

import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.SwitchPort;

import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;

import net.floodlightcontroller.util.NodePortTuple;

public class PacketInManager implements IOFMessageListener, IFloodlightModule {

    protected IFloodlightProviderService floodlightProvider;
    protected IDeviceService deviceService;
    protected IRoutingService routingService;

    // Valor estándar de la opción 53 para DHCPDISCOVER
    private static final byte DHCPDISCOVER = 1;

    // IP del servidor DHCP en tu topología (AJÚSTALA A TU CASO)
    private static final IPv4Address DHCP_SERVER_IP = IPv4Address.of("10.0.0.254");

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

        if (eth == null) {
            System.out.println("⚠ No se pudo obtener el payload Ethernet del contexto");
            return Command.CONTINUE;
        }

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
            System.out.println("ARP Sender IP : " + IPv4Address.of(arp.getSenderProtocolAddress()));
            System.out.println("ARP Target IP : " + IPv4Address.of(arp.getTargetProtocolAddress()));
        }

        // --- IPv4 ---
        if (etherType == EthType.IPv4) {
            IPv4 ipv4 = (IPv4) eth.getPayload();
            System.out.println("--- IPv4 INFO ---");
            System.out.println("SRC IP        : " + ipv4.getSourceAddress());
            System.out.println("DST IP        : " + ipv4.getDestinationAddress());
            System.out.println("L4 Protocol   : " + ipv4.getProtocol());

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

                if (isDhcpClientToServer) {
                    System.out.println("Posible DHCP cliente→servidor (68→67)");

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

                            // 🔥 Aquí llamamos a la lógica que conecta h1 con el servidor DHCP
                            instalarCaminoDhcp(sw.getId(), inPort);
                        }
                    } else {
                        System.out.println("UDP payload NO es instancia de DHCP: "
                                + udp.getPayload().getClass().getName());
                    }
                }
            }
        }

        System.out.println("====================================\n");

        return Command.CONTINUE;
    }

    /**
     * Usa la API interna de Floodlight para:
     * 1) Encontrar al servidor DHCP (IDeviceService, por IP).
     * 2) Obtener la ruta host→DHCP (IRoutingService).
     * 3) Programar flows de DHCP (68→67 y 67→68) a lo largo de la ruta.
     */
    private void instalarCaminoDhcp(DatapathId hostSwId, OFPort hostPort) {

        // 1) Buscar el dispositivo que tenga la IP del servidor DHCP
        IDevice dhcpDevice = null;
        for (IDevice dev : deviceService.getAllDevices()) {
            IPv4Address[] ips = dev.getIPv4Addresses();
            if (ips == null) continue;
            for (IPv4Address ip : ips) {
                if (ip.equals(DHCP_SERVER_IP)) {
                    dhcpDevice = dev;
                    break;
                }
            }
            if (dhcpDevice != null) break;
        }

        if (dhcpDevice == null) {
            System.out.println("⚠ No se encontró el servidor DHCP en IDeviceService para IP " +
                               DHCP_SERVER_IP);
            return;
        }

        // 2) Tomar el primer attachment point del servidor DHCP
        SwitchPort[] apDhcp = dhcpDevice.getAttachmentPoints();
        if (apDhcp == null || apDhcp.length == 0) {
            System.out.println("⚠ Servidor DHCP sin attachment point registrado");
            return;
        }

        DatapathId dhcpSwId = apDhcp[0].getSwitchDPID();
        OFPort     dhcpPort = apDhcp[0].getPort();

        System.out.println("DHCP server visto en switch " + dhcpSwId + " puerto " + dhcpPort);

        // 3) Obtener la ruta host → servidor DHCP
        Route route = routingService.getRoute(
                hostSwId, hostPort,
                dhcpSwId, dhcpPort,
                U64.ZERO);

        if (route == null || route.getPath() == null || route.getPath().isEmpty()) {
            System.out.println("⚠ No se pudo calcular ruta entre host (" + hostSwId + ":" + hostPort +
                               ") y DHCP (" + dhcpSwId + ":" + dhcpPort + ")");
            return;
        }

        List<NodePortTuple> path = route.getPath();
        System.out.println("Ruta DHCP calculada: " + path.toString());

        // 4) Recorrer la ruta y programar flows de DHCP
        //    path = [ (sw1, in1), (sw1, out1), (sw2, in2), (sw2, out2), ... ]
        for (int i = 0; i < path.size(); i += 2) {

            DatapathId swId   = path.get(i).getNodeId();
            OFPort     inP    = path.get(i).getPortId();
            OFPort     outP   = path.get(i+1).getPortId();

            IOFSwitch s = floodlightProvider.getSwitch(swId);
            if (s == null) {
                System.out.println("⚠ Switch " + swId + " no disponible al programar flows DHCP");
                continue;
            }

            OFFactory of = s.getOFFactory();

            // ----- Flow cliente → servidor (UDP 68 -> 67) -----
            org.projectfloodlight.openflow.protocol.match.Match matchC2S =
                    of.buildMatch()
                      .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                      .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                      .setExact(MatchField.UDP_SRC, UDP.DHCP_CLIENT_PORT) // 68
                      .setExact(MatchField.UDP_DST, UDP.DHCP_SERVER_PORT) // 67
                      .setExact(MatchField.IN_PORT, inP)
                      .build();

            List<OFAction> actionsC2S = new ArrayList<>();
            actionsC2S.add(of.actions().output(outP, Integer.MAX_VALUE));

            List<OFInstruction> instC2S = Collections.singletonList(
                    of.instructions().applyActions(actionsC2S));

            OFFlowAdd flowC2S = of.buildFlowAdd()
                    .setPriority(100)
                    .setMatch(matchC2S)
                    .setInstructions(instC2S)
                    .build();

            s.write(flowC2S);

            // ----- Flow servidor → cliente (UDP 67 -> 68) -----
            org.projectfloodlight.openflow.protocol.match.Match matchS2C =
                    of.buildMatch()
                      .setExact(MatchField.ETH_TYPE, EthType.IPv4)
                      .setExact(MatchField.IP_PROTO, IpProtocol.UDP)
                      .setExact(MatchField.UDP_SRC, UDP.DHCP_SERVER_PORT) // 67
                      .setExact(MatchField.UDP_DST, UDP.DHCP_CLIENT_PORT) // 68
                      // ahora el paquete entra por outP y sale por inP (camino inverso)
                      .setExact(MatchField.IN_PORT, outP)
                      .build();

            List<OFAction> actionsS2C = new ArrayList<>();
            actionsS2C.add(of.actions().output(inP, Integer.MAX_VALUE));

            List<OFInstruction> instS2C = Collections.singletonList(
                    of.instructions().applyActions(actionsS2C));

            OFFlowAdd flowS2C = of.buildFlowAdd()
                    .setPriority(100)
                    .setMatch(matchS2C)
                    .setInstructions(instS2C)
                    .build();

            s.write(flowS2C);

            System.out.println("✓ Flows DHCP instalados en switch " + swId +
                               " (in=" + inP + ", out=" + outP + ")");
        }
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
        deps.add(IDeviceService.class);
        deps.add(IRoutingService.class);
        return deps;
    }

    @Override
    public void init(FloodlightModuleContext context)
            throws FloodlightModuleException {
        this.floodlightProvider =
                context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceService =
                context.getServiceImpl(IDeviceService.class);
        this.routingService =
                context.getServiceImpl(IRoutingService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context)
            throws FloodlightModuleException {

        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        System.out.println("=== PacketInManager cargado (logueando PACKET_IN y programando camino DHCP) ===");
    }
}
