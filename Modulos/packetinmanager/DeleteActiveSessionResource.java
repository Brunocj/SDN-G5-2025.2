package net.floodlightcontroller.packetinmanager;

import java.util.HashMap;
import java.util.Map;

import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.restlet.representation.Representation;

public class DeleteActiveSessionResource extends ServerResource {

    @Post("json")
    public Map<String, Object> deleteActiveSession(Representation entity) {
        Map<String, Object> resp = new HashMap<>();

        try {
            // Leemos el JSON { "ip": "10.0.0.X" }
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

            // Llamamos al helper para borrar de la BD
            // (Asegúrate de implementar este método en SecurityHelper)
            SecurityHelper.eliminarSesionActiva(ip);

            resp.put("success", true);
            resp.put("ip", ip);
            return resp;

        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", "Error eliminando sesión: " + e.getMessage());
            return resp;
        }
    }
}