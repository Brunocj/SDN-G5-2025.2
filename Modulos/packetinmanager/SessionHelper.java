package net.floodlightcontroller.packetinmanager;

import java.sql.*;

public class SessionHelper {

    private static final String DB_HOST = "127.0.0.1";
    private static final String DB_PORT = "3307";
    private static final String DB_NAME = "sesiones";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "rootroot";

    private static final String JDBC_URL =
        "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME +
        "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("[SesionesDB] Driver JDBC cargado para SessionHelper");
        } catch (ClassNotFoundException e) {
            System.err.println("[SesionesDB] ERROR cargando driver JDBC en SessionHelper");
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
    }

    public static void crearEntrada(String ip, String mac, String switchid, String inport) {

        String sql = "INSERT INTO posibles_sesiones (ip, mac, switchid, inport) " +
                     "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {

            st.setString(1, ip);
            st.setString(2, mac);
            st.setString(3, switchid);
            st.setString(4, inport);

            int filas = st.executeUpdate();
            System.out.println("[SessionHelper] Posible sesión creada. Filas afectadas: " + filas);

        } catch (SQLIntegrityConstraintViolationException e) {
            // Si existe UNIQUE(ip) o UNIQUE(ip,mac) entonces:
            System.out.println("[SessionHelper] La sesión para IP " + ip + " ya existe. No se actualiza.");
        } catch (SQLException e) {
            System.err.println("[SessionHelper] ERROR creando posible sesión");
            e.printStackTrace();
        }
    }

    /**
     * Obtiene el userID asociado a la IP desde la tabla sesiones_activas.
     *
     * @param ip IP del host (ej. "10.0.0.5")
     * @return el userID asociado (Integer), o null si no existe o no es numérico
     */
    public static Integer obtenerUserId(String ip) {

        String sql = "SELECT userID FROM sesiones_activas " +
                    "WHERE ip = ? " +
                    "ORDER BY idsesiones_activas DESC " +
                    "LIMIT 1";

        try (Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ip);

            try (ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    String userIdStr = rs.getString("userID");
                    try {
                        Integer userId = Integer.parseInt(userIdStr);
                        System.out.println("[SesionesDB] obtenerUserId(" + ip + ") -> " + userId);
                        return userId;
                    } catch (NumberFormatException ex) {
                        System.err.println("[SesionesDB] userID no es numérico para IP " + ip +
                                        " (valor='" + userIdStr + "')");
                        return null;
                    }
                } else {
                    System.out.println("[SesionesDB] obtenerUserId(" + ip + ") -> NO EXISTE en sesiones_activas");
                    return null;
                }
            }

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR en obtenerUserId(" + ip + ")");
            e.printStackTrace();
            return null;
        }
    }

}
