package net.floodlightcontroller.packetinmanager;

import java.util.HashMap;
import java.util.Map;

import org.restlet.resource.Get;
import org.restlet.resource.ServerResource;

public class PossibleSessionResource extends ServerResource {

    @Get("json")
    public Map<String, Object> getPossibleSession() {
        String ip = getQueryValue("ip");
        Map<String, Object> resp = new HashMap<>();

        if (ip == null || ip.isEmpty()) {
            resp.put("success", false);
            resp.put("error", "Parametro 'ip' es obligatorio");
            return resp;
        }

        String[] params = SecurityHelper.obtenerParametrosDesdePosibles(ip);

        if (params == null) {
            // 👈 hubo error SQL
            resp.put("success", false);
            resp.put("ip", ip);
            resp.put("error", "Error interno al consultar la base de datos (revisar logs de SesionesDB)");
            return resp;
        }

        if (params.length == 0) {
            // 👈 no hay fila
            resp.put("success", false);
            resp.put("ip", ip);
            resp.put("error", "No se encontró posible_sesion para esa IP");
            return resp;
        }

        resp.put("success", true);
        resp.put("ip", params[0]);
        resp.put("mac", params[1]);
        resp.put("switchid", params[2]);
        resp.put("inport", params[3]);

        return resp;
    }
}
