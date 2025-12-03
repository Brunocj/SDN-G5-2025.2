package net.floodlightcontroller.packetinmanager.web;

import java.util.List;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.floodlightcontroller.packetinmanager.IAccessControlService;

public class ResourcePushResource extends ServerResource {
    protected static Logger log = LoggerFactory.getLogger(ResourcePushResource.class);

    @Post("json")
    public String pushResources(String json) {
        IAccessControlService aclService = (IAccessControlService) getContext().getAttributes()
                .get(IAccessControlService.class.getCanonicalName());

        if (aclService == null) {
            return "{\"status\": \"error\", \"msg\": \"Servicio ACL no disponible\"}";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            
            String userIpStr = root.get("user_ip").asText();
            IPv4Address userIp = IPv4Address.of(userIpStr);

            JsonNode resourcesNode = root.get("resources");
            List<AllowedResource> resources = mapper.convertValue(resourcesNode, 
                    mapper.getTypeFactory().constructCollectionType(List.class, AllowedResource.class));

            // Llamamos a la logica maestra
            aclService.allowAccessToResources(userIp, resources);

            return "{\"status\": \"ok\", \"msg\": \"Politicas aplicadas para " + userIpStr + "\"}";

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"status\": \"error\", \"msg\": \"Error procesando JSON: " + e.getMessage() + "\"}";
        }
    }
}