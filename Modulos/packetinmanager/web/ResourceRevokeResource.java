package net.floodlightcontroller.packetinmanager.web;

import java.io.IOException;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.floodlightcontroller.packetinmanager.IAccessControlService;

public class ResourceRevokeResource extends ServerResource {
    protected static Logger log = LoggerFactory.getLogger(ResourceRevokeResource.class);

    @Post("json")
    public String revokeAccess(String json) {
        IAccessControlService aclService = (IAccessControlService) getContext().getAttributes()
                .get(IAccessControlService.class.getCanonicalName());

        if (aclService == null) {
            return "{\"status\": \"error\", \"msg\": \"Servicio ACL no disponible\"}";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            
            // Solo necesitamos la IP para borrar todo lo de ese usuario
            String userIpStr = root.get("user_ip").asText();
            IPv4Address userIp = IPv4Address.of(userIpStr);

            // Llamamos al metodo de borrado
            aclService.revokeAccess(userIp);

            return "{\"status\": \"ok\", \"msg\": \"Acceso revocado para " + userIpStr + "\"}";

        } catch (Exception e) {
            return "{\"status\": \"error\", \"msg\": \"Error: " + e.getMessage() + "\"}";
        }
    }
}