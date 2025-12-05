package net.floodlightcontroller.packetinmanager;

import java.sql.*;

public class PoliciesHelper {

    // Datos de conexión a tu DB en H7
    private static final String DB_HOST = "192.168.201.217";
    private static final String DB_PORT = "3306";
    private static final String DB_NAME = "sdn_policy";
    private static final String DB_USER = "dti_dodo";
    private static final String DB_PASS = "ubuntu";

    private static final String JDBC_URL =
        "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME +
        "?useSSL=false&serverTimezone=UTC";

    // Cargar driver
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("[PoliciesDB] Driver JDBC MySQL cargado.");
            System.out.println("[PoliciesDB] JDBC_URL = " + JDBC_URL);
        } catch (ClassNotFoundException e) {
            System.err.println("[PoliciesDB] ERROR cargando driver JDBC MySQL");
            e.printStackTrace();
        }
    }

    // Obtener conexión igual que en SecurityHelper
    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
    }


    /**
     * Verifica los permisos de acceso de un usuario hacia un recurso IP:port.
     *
     * @param userId ID del usuario
     * @param ip     IP del recurso
     * @param port   Puerto del recurso
     * @return "NOT_FOUND", "ACCESS_DENIED", o "ip|port"
     */
    public static String checkAccess(int userId, String ip, int port) {
        String sql =
            "SELECT CASE " +
            "   WHEN r.id IS NULL THEN 'NOT_FOUND' " +

            // --- CHEQUEO DE PERMISOS (Igual que antes) ---
            "   WHEN EXISTS ( " +
            "       SELECT 1 FROM resources_roles rr " +
            "       JOIN user_roles ur ON ur.role_id = rr.role_id " +
            "       WHERE rr.resource_id = r.id AND ur.user_id = ? " +
            "   ) THEN 'ALLOWED' " +  // <--- CAMBIO: Retornamos ALLOWED en vez de la IP
            
            "   WHEN EXISTS ( " +
            "       SELECT 1 FROM course_resources cr " +
            "       JOIN user_courses uc ON uc.course_id = cr.course_id " +
            "       WHERE cr.resource_id = r.id AND uc.user_id = ? " +
            "   ) THEN 'ALLOWED' " +

            "   WHEN EXISTS ( " +
            "       SELECT 1 FROM user_resources ures " +
            "       WHERE ures.resource_id = r.id AND ures.user_id = ? " +
            "   ) THEN 'ALLOWED' " +

            // --- CAMBIO CLAVE: Retornamos la criticidad en el rechazo ---
            // Concatenamos 'DENIED:' con el valor de la columna 'critical' (0 o 1)
            "   ELSE CONCAT('DENIED:', r.critical) " +
            "END AS result " +
            "FROM resources r " +
            "WHERE r.server_ip = ? AND r.server_port = ? " +
            "LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // Insertar parámetros
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setString(4, ip);
            ps.setInt(5, port);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String result = rs.getString("result");
                    System.out.println("[PoliciesDB] checkAccess -> " + result);
                    return result;
                } else {
                    System.out.println("[PoliciesDB] checkAccess -> SIN FILAS, retornando NOT_FOUND");
                    return "NOT_FOUND";
                }
            }

        } catch (SQLException e) {
            System.err.println("[PoliciesDB] ERROR en checkAccess(" +
                                userId + ", " + ip + ", " + port + ")");
            e.printStackTrace();
            return "DB_ERROR";
        }
    }

    /**
     * Método puente para flujos reactivos.
     * 1. Traduce IP Origen -> UserID (usando SessionHelper).
     * 2. Verifica permiso granular IP:Puerto (usando checkAccess).
     */
    public static boolean verificarAccesoRecurso(String srcIp, String dstIp, int dstPort) {
        
        // 1. Averiguar quién es el usuario a partir de su IP
        // Usamos SessionHelper que ya se conecta a la BD 'sesiones'
        Integer userId = SessionHelper.obtenerUserId(srcIp);
        
        if (userId == null) {
            // Si no hay ID, el usuario no está logueado o expiró su sesión.
            return false;
        }

        // 2. Verificar permiso en la BD de políticas
        String resultado = checkAccess(userId, dstIp, dstPort);

        // 3. Interpretar la respuesta SQL
        // Si la BD devuelve "10.0.0.50|80" es un SÍ. Todo lo demás es NO.
        if (resultado.equals("ACCESS_DENIED") || 
            resultado.equals("NOT_FOUND") || 
            resultado.equals("DB_ERROR")) {
            return false;
        }

        return true;
    }

    /**
     * Guarda el log en la BD (Async).
     * CORREGIDO PARA JAVA 1.7 (Sin lambdas, con Runnable anónimo y variables final)
     */
    public static void insertarLog(final String username, final String role, final String srcIp, final String dstIp, final int dstPort, final String action, final String details) {
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                String sql = "INSERT INTO access_logs (username, role, src_ip, dst_ip, dst_port, action, details) VALUES (?, ?, ?, ?, ?, ?, ?)";
                
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    
                    ps.setString(1, (username != null) ? username : "Unknown");
                    ps.setString(2, (role != null) ? role : "Unknown");
                    ps.setString(3, srcIp);
                    ps.setString(4, dstIp);
                    ps.setInt(5, dstPort);
                    ps.setString(6, action);
                    ps.setString(7, details);
                    
                    ps.executeUpdate();
                    
                } catch (SQLException e) {
                    System.err.println("[PoliciesDB] Error log: " + e.getMessage());
                }
            }
        }).start();
    }
}
