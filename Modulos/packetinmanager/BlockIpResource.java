package net.floodlightcontroller.packetinmanager;

import java.util.HashMap;
import java.util.Map;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.restlet.representation.Representation;
import org.projectfloodlight.openflow.types.IPv4Address;

public class BlockIpResource extends ServerResource {

    @Post("json")
    public Map<String, Object> blockIp(Representation entity) {
        Map<String, Object> resp = new HashMap<>();

        // 🔴 CORRECCIÓN AQUÍ:
        // No buscamos "PacketInManager.class", buscamos la INTERFAZ "IAccessControlService.class"
        // que es como Floodlight registra el servicio en el contexto.
        IAccessControlService mgr = (IAccessControlService) getContext().getAttributes()
                .get(IAccessControlService.class.getCanonicalName());

        // Verificación de seguridad para evitar otro NullPointerException
        if (mgr == null) {
            resp.put("success", false);
            resp.put("error", "Error CRÍTICO: No se pudo obtener el servicio IAccessControlService del contexto.");
            return resp;
        }

        try {
            String jsonStr = entity.getText();
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, String> body = mapper.readValue(jsonStr, Map.class);
            
            String ip = body.get("ip");
            
            if (ip == null || ip.isEmpty()) {
                resp.put("success", false);
                resp.put("error", "Falta el parámetro 'ip'");
                return resp;
            }

            // Ahora sí podemos llamar al método porque lo agregamos a la interfaz
            mgr.blockIpAddress(IPv4Address.of(ip));

            resp.put("success", true);
            resp.put("msg", "Orden de bloqueo enviada correctamente para: " + ip);
            return resp;

        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", "Excepción procesando solicitud: " + e.getMessage());
            return resp;
        }
    }
}