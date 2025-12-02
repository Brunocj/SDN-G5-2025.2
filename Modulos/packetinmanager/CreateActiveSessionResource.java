package net.floodlightcontroller.packetinmanager;

import java.util.HashMap;
import java.util.Map;

import org.restlet.data.Form;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.restlet.representation.Representation;


public class CreateActiveSessionResource extends ServerResource {

    @Post("json")
    public Map<String, Object> createActiveSession(Representation entity) {
        Map<String, Object> resp = new HashMap<>();

        try {
            // Convertimos el body JSON a String
            String jsonStr = entity.getText();

            // Parseamos JSON a un Map
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            Map<String, String> body = mapper.readValue(jsonStr, Map.class);

            String ip       = body.get("ip");
            String mac      = body.get("mac");
            String switchid = body.get("switchid");
            String inport   = body.get("inport");
            String userId   = body.get("userId");

            if (ip == null || mac == null || switchid == null || inport == null || userId == null) {
                resp.put("success", false);
                resp.put("error", "Faltan parámetros obligatorios en el JSON");
                return resp;
            }

            // Llamamos a tu método real
            SecurityHelper.crearSesionActiva(ip, mac, switchid, inport, userId);

            resp.put("success", true);
            resp.put("ip", ip);
            resp.put("mac", mac);
            resp.put("switchid", switchid);
            resp.put("inport", inport);
            resp.put("userId", userId);
            return resp;

        } catch (Exception e) {
            e.printStackTrace();
            resp.put("success", false);
            resp.put("error", "Error procesando JSON");
            return resp;
        }
    }

}
