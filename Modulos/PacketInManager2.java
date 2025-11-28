package net.floodlightcontroller.packetinmanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.DHCPOption;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;

public class PacketInManager implements IOFMessageListener, IFloodlightModule {

    protected IFloodlightProviderService floodlightProvider;

    // DHCP Constants
    private static final byte DHCPDISCOVER = 1;
    private static final byte DHCPACK      = 5;

    // Configuración del Portal
    private static final IPv4Address PORTAL_IP = IPv4Address.of("10.0.0.7");
    private static final TransportPort HTTP_PORT = TransportPort.of(80);

    // Lista de clientes autorizados (Thread-safe)
    private final Set<IPv4Address> allowedClients =
            Collections.newSetFromMap(new ConcurrentHashMap<IPv4Address, Boolean>());

    @Override
    public String getName() {
        return PacketInManager.class.getSimpleName();
    }

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) { return false; }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) { return false; }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {

        if (msg.getType() != OFType.PACKET_IN) {
            return Command.CONTINUE;
        }

        OFPacketIn pi = (OFPacketIn) msg;
        Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
        
        OFPort inPort = pi.getMatch().get(MatchField.IN_PORT);
        EthType etherType = eth.getEtherType();

        // 1. Dejar pasar ARP (Esencial para descubrir MACs)
        if (etherType == EthType.ARP) {
            floodPacket(sw, pi, inPort);
            return Command.STOP;
        }

        // 2. Procesar IPv4
        if (etherType == EthType.IPv4) {
            IPv4 ipv4 = (IPv4) eth.getPayload();
            IPv4Address srcIp = ipv4.getSourceAddress();
            IPv4Address dstIp = ipv4.getDestinationAddress();

            // -----------------------------------------------------------
            // A) Lógica DHCP (UDP 67/68) - Detectar IPs y Autorizar
            // -----------------------------------------------------------
            if (ipv4.getProtocol() == IpProtocol.UDP) {
                UDP udp = (UDP) ipv4.getPayload();
                TransportPort srcPort = udp.getSourcePort();
                TransportPort dstPort = udp.getDestinationPort();

                if ((srcPort.equals(UDP.DHCP_CLIENT_PORT) && dstPort.equals(UDP.DHCP_SERVER_PORT)) ||
                    (srcPort.equals(UDP.DHCP_SERVER_PORT) && dstPort.equals(UDP.DHCP_CLIENT_PORT))) {
                    
                    handleDhcp(ipv4, udp); // Procesa lógica interna (logs y allowList)
                    floodPacket(sw, pi, inPort); // Deja pasar el paquete DHCP
                    return Command.STOP;
                }
            }

            // -----------------------------------------------------------
            // B) Lógica de Portal Cautivo y Redirección
            // -----------------------------------------------------------
            
            // Caso 1: El paquete viene DEL Portal hacia un cliente
            if (srcIp.equals(PORTAL_IP)) {
                floodPacket(sw, pi, inPort);
                return Command.STOP;
            }

            // Caso 2: El cliente YA está autorizado
            if (allowedClients.contains(srcIp)) {
                // Si está autorizado, dejamos pasar su tráfico (Flood simple)
                // OJO: Aquí podrías filtrar solo hacia el portal o internet si tuvieras gateway
                floodPacket(sw, pi, inPort);
                return Command.STOP;
            }

            // Caso 3: Cliente NO autorizado intenta navegar (HTTP / TCP 80)
            if (ipv4.getProtocol() == IpProtocol.TCP) {
                TCP tcp = (TCP) ipv4.getPayload();
                
                // Si intenta ir a una web (Port 80) y NO es el portal
                if (tcp.getDestinationPort().equals(HTTP_PORT) && !dstIp.equals(PORTAL_IP)) {
                    
                    System.out.println(">>> REDIRECCIONANDO CLIENTE NO AUTORIZADO: " + srcIp + 
                                       " intentaba ir a " + dstIp + " -> Redirigiendo a " + PORTAL_IP);
                    
                    // Magia: Cambiar IP destino y reenviar
                    redirectToPortal(sw, pi, inPort);
                    return Command.STOP;
                }
            }
            
            // Caso 4: Cliente NO autorizado yendo explícitamente al Portal
            if (dstIp.equals(PORTAL_IP)) {
                floodPacket(sw, pi, inPort);
                return Command.STOP;
            }
        }

        // Si no coincide con nada (ej. ping a google sin estar logueado), se descarta implícitamente
        return Command.CONTINUE;
    }

    /**
     * Analiza paquetes DHCP para poblar la lista de allowedClients
     */
    private void handleDhcp(IPv4 ipv4, UDP udp) {
        if (udp.getPayload() instanceof DHCP) {
            DHCP dhcp = (DHCP) udp.getPayload();
            byte msgType = -1;
            
            for (DHCPOption opt : dhcp.getOptions()) {
                if (opt.getCode() == DHCP.DHCPOptionCode.OptionCode_MessageType.getValue()) {
                    msgType = opt.getData()[0];
                    break;
                }
            }

            if (msgType == DHCPDISCOVER) {
                System.out.println("DHCP DISCOVER detectado de: " + ipv4.getSourceAddress());
            } else if (msgType == DHCPACK) {
                IPv4Address clientIp = dhcp.getYourIPAddress();
                if (clientIp != null && !clientIp.equals(IPv4Address.NONE)) {
                    System.out.println("DHCP ACK -> Asignando IP: " + clientIp + ". Agregado a AllowList (simulado).");
                    // OJO: En un portal real, aquí NO se autoriza. Se autoriza cuando pone usuario/pass.
                    // Pero para tu lógica actual, mantenemos esto:
                    allowedClients.add(clientIp);
                }
            }
        }
    }

    /**
     * PacketOut Genérico (FLOOD) sin modificar nada
     */
    private void floodPacket(IOFSwitch sw, OFPacketIn pi, OFPort inPort) {
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        pob.setBufferId(pi.getBufferId());
        pob.setInPort(inPort);

        List<OFAction> actions = new ArrayList<>();
        actions.add(sw.getOFFactory().actions().output(OFPort.FLOOD, 0xFFFF));
        pob.setActions(actions);
        pob.setData(pi.getData());

        sw.write(pob.build());
    }

    /**
     * PacketOut con Modificación de Cabecera (DNAT)
     * Cambia la IP de destino a la IP del Portal y hace FLOOD.
     */
    private void redirectToPortal(IOFSwitch sw, OFPacketIn pi, OFPort inPort) {
        OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
        pob.setBufferId(pi.getBufferId());
        pob.setInPort(inPort);

        List<OFAction> actions = new ArrayList<>();

        // 1. Crear acción SET_FIELD para cambiar NW_DST (IP Destino)
        OFOxms oxms = sw.getOFFactory().oxms();
        OFActionSetField setDstIp = sw.getOFFactory().actions().buildSetField()
                .setField(oxms.ipv4Dst(PORTAL_IP))
                .build();
        
        actions.add(setDstIp);

        // 2. Enviar por inundación (el switch recalcula checksum IP automáticamente tras el set-field)
        actions.add(sw.getOFFactory().actions().output(OFPort.FLOOD, 0xFFFF));

        pob.setActions(actions);
        pob.setData(pi.getData());

        sw.write(pob.build());
    }

    // --- Métodos de ciclo de vida del módulo ---

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() { return null; }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() { return null; }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        List<Class<? extends IFloodlightService>> deps = new ArrayList<>();
        deps.add(IFloodlightProviderService.class);
        return deps;
    }

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
        floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
        System.out.println("=== PacketInManager INICIADO ===");
        System.out.println("-> Portal Cautivo en: " + PORTAL_IP);
        System.out.println("-> Modo: Redirección HTTP (TCP 80)");
    }
}
