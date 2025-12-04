package net.floodlightcontroller.packetinmanager;

import java.sql.*;

public class SecurityHelper {

    // Ajusta estos valores a tu contenedor Docker
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
            Class.forName("com.mysql.cj.jdbc.Driver"); // MySQL 8.x
            System.out.println("[SesionesDB] Driver JDBC MySQL cargado.");
            System.out.println("[SesionesDB] JDBC_URL = " + JDBC_URL);

            // 🔎 Test rápido al arrancar
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS c FROM posibles_sesiones");
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    int count = rs.getInt("c");
                    System.out.println("[SesionesDB] Test conexión OK. posibles_sesiones tiene " + count + " filas.");
                }
            } catch (SQLException e) {
                System.err.println("[SesionesDB] ERROR en test de conexión inicial.");
                e.printStackTrace();
            }

        } catch (ClassNotFoundException e) {
            System.err.println("[SesionesDB] ERROR cargando driver JDBC MySQL");
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
    }

    /**
     * Crea una entrada en la tabla posibles_sesiones.
     * El idposibles_sesiones se autogenera (AUTO_INCREMENT).
     *
     * @param ip       IP del host (ej. "10.0.0.5")
     * @param mac      MAC del host (ej. "aa:bb:cc:dd:ee:ff")
     * @param switchid DPID del switch (ej. "00:00:11:22:33:44:55:66")
     * @param inport   Puerto de entrada donde se vio al host (ej. "1")
     */
    public static void crearPosibleSesion(String ip,
                                          String mac,
                                          String switchid,
                                          String inport) {

        String sql = "INSERT INTO posibles_sesiones (ip, mac, switchid, inport) " +
                     "VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ip);
            ps.setString(2, mac);
            ps.setString(3, switchid);
            ps.setString(4, inport);

            int filas = ps.executeUpdate();
            System.out.println("[SesionesDB] crearPosibleSesion -> filas afectadas: " + filas);

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR al crear posible_sesion");
            e.printStackTrace();
        }
    }

    /**
     * Crea una entrada en la tabla sesiones_activas a partir de la IP,
     * usando la información almacenada en posibles_sesiones.
     *
     * Flujo:
     *  1) SELECT * FROM posibles_sesiones WHERE ip = ?
     *  2) INSERT INTO sesiones_activas (ip, mac, switchid, inport, userID)
     *     VALUES (...., idUsuario)
     *  3) DELETE FROM posibles_sesiones WHERE idposibles_sesiones = ?
     *
     * Todo se hace en una transacción (commit/rollback).
     *
     * @param ip        IP del host (ej. "10.0.0.5")
     * @param idUsuario ID del usuario autenticado (ej. "user123")
     */
    public static String[] obtenerParametrosDesdePosibles(String ip) {
        String selectSql = "SELECT ip, mac, switchid, inport " +
                           "FROM posibles_sesiones WHERE ip = ? " +
                           "ORDER BY idposibles_sesiones DESC LIMIT 1";

        try (Connection conn = getConnection();
             PreparedStatement psSelect = conn.prepareStatement(selectSql)) {

            System.out.println("[SesionesDB] Ejecutando obtenerParametrosDesdePosibles(" + ip + ")");
            psSelect.setString(1, ip);

            try (ResultSet rs = psSelect.executeQuery()) {
                if (rs.next()) {
                    String ipDb     = rs.getString("ip");
                    String mac      = rs.getString("mac");
                    String switchid = rs.getString("switchid");
                    String inport   = rs.getString("inport");

                    String[] valores = new String[4];
                    valores[0] = ipDb;
                    valores[1] = mac;
                    valores[2] = switchid;
                    valores[3] = inport;

                    System.out.println("[SesionesDB] Resultado: ip=" + ipDb +
                        ", mac=" + mac + ", switchid=" + switchid + ", inport=" + inport);
                    return valores;
                } else {
                    System.out.println("[SesionesDB] No se encontró posible_sesion para IP " + ip);
                    return new String[0];  // 👈 diferencia "no hay fila" de "error"
                }
            }

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR en obtenerParametrosDesdePosibles(" + ip + ")");
            e.printStackTrace();
            return null; // 👈 esto será tratado como "error SQL"
        }
    }

    public static void crearSesionActiva(String ip,
                                        String mac,
                                        String switchid,
                                        String inport,
                                        String idUsuario) {

        String insertSql = "INSERT INTO sesiones_activas (ip, mac, switchid, inport, userID) " +
                        "VALUES (?, ?, ?, ?, ?)";

        // Borramos por la combinación de campos
        String deleteSql = "DELETE FROM posibles_sesiones " +
                        "WHERE ip = ? AND mac = ? AND switchid = ? AND inport = ?";

        Connection conn = null;

        try {
            conn = getConnection();
            conn.setAutoCommit(false); // ⭐ transacción

            // 1) Insertar en sesiones_activas
            try (PreparedStatement psInsert = conn.prepareStatement(insertSql)) {
                psInsert.setString(1, ip);
                psInsert.setString(2, mac);
                psInsert.setString(3, switchid);
                psInsert.setString(4, inport);
                psInsert.setString(5, idUsuario);

                int filasIns = psInsert.executeUpdate();
                System.out.println("[SesionesDB] Insert en sesiones_activas para IP " + ip +
                                " -> filas afectadas: " + filasIns);
            }

            // 2) Borrar la(s) fila(s) correspondiente(s) en posibles_sesiones
            try (PreparedStatement psDelete = conn.prepareStatement(deleteSql)) {
                psDelete.setString(1, ip);
                psDelete.setString(2, mac);
                psDelete.setString(3, switchid);
                psDelete.setString(4, inport);

                int filasDel = psDelete.executeUpdate();
                System.out.println("[SesionesDB] Delete en posibles_sesiones para " +
                                ip + " / " + mac + " / " + switchid + " / " + inport +
                                " -> filas afectadas: " + filasDel);
            }

            conn.commit();
            System.out.println("[SesionesDB] crearSesionActiva(" + ip +
                            ", " + mac + ", " + switchid + ", " + inport +
                            ", " + idUsuario + ") COMPLETADO OK.");

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR en crearSesionActiva(" + ip +
                            ", " + mac + ", " + switchid + ", " + inport +
                            ", " + idUsuario + ")");
            e.printStackTrace();
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("[SesionesDB] Rollback ejecutado.");
                } catch (SQLException ex) {
                    System.err.println("[SesionesDB] ERROR haciendo rollback");
                    ex.printStackTrace();
                }
            }
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }
    }



    /**
     * Verifica si una MAC ya existe en la tabla sesiones_activas.
     *
     * @param mac MAC a verificar (ej. "aa:bb:cc:dd:ee:ff")
     * @return true  si existe al menos una entrada con esa MAC
     *         false si no existe ninguna o si ocurre un error
     */
    public static boolean checkMacSpoofing(String mac) {
        String sql = "SELECT 1 FROM sesiones_activas WHERE mac = ? LIMIT 1";

        try (Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, mac);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    // Hay al menos una sesión activa con esa MAC
                    System.out.println("[SesionesDB] MAC " + mac +
                                    " encontrada en sesiones_activas.");
                    return true;
                } else {
                    System.out.println("[SesionesDB] MAC " + mac +
                                    " NO encontrada en sesiones_activas.");
                    return false;
                }
            }

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR en checkMacSpoofing(" + mac + ")");
            e.printStackTrace();
            // Por simplicidad, en error devolvemos false
            return false;
        }
    }

    /**
     * Verificación completa de sesión:
     *
     *  1 → Spoofing:
     *      - Existe IP o MAC en la BD, pero NO coincide switchid/inport
     *        o el otro campo.
     *
     *  2 → OK (solicita flows):
     *      - Coincidencia EXACTA de IP + MAC + switchid + inport
     *
     *  3 → Error de usuario:
     *      - NO existe ni la IP ni la MAC en la BD
     *
     */
    public static int verificarSesion(String ip,
                                    String mac,
                                    String switchid,
                                    String inport) {

        boolean ipExiste  = existeIp(ip);
        boolean macExiste = existeMac(mac);

        // CASO 3 → ERROR USUARIO (no existe IP NI MAC)
        if (!ipExiste && !macExiste) {
            System.out.println("[SesionesDB] Caso 3: ERROR_USUARIO – No existe IP ni MAC");
            return 3;
        }

        // CASO 2 → OK (coincidencia completa)
        if (existeCoincidenciaCompleta(ip, mac, switchid, inport)) {
            System.out.println("[SesionesDB] Caso 2: OK – Coincidencia exacta");
            return 2;
        }

        // CASO 1 → SPOOFING
        System.out.println("[SesionesDB] Caso 1: SPOOFING – IP o MAC existe, pero no coincide todo");
        return 1;
    }

    private static boolean existeIp(String ip) {
        String sql = "SELECT 1 FROM sesiones_activas WHERE ip = ? LIMIT 1";
        try (Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean existeMac(String mac) {
        String sql = "SELECT 1 FROM sesiones_activas WHERE mac = ? LIMIT 1";
        try (Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, mac);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean existeCoincidenciaCompleta(String ip,
                                                    String mac,
                                                    String switchid,
                                                    String inport) {

        String sql = "SELECT 1 FROM sesiones_activas " +
                    "WHERE ip=? AND mac=? AND switchid=? AND inport=? LIMIT 1";

        try (Connection conn = getConnection();
            PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ip);
            ps.setString(2, mac);
            ps.setString(3, switchid);
            ps.setString(4, inport);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Elimina una sesión activa basada en la IP.
     * Se usa cuando el usuario hace Logout en el portal.
     *
     * @param ip IP del usuario que cierra sesión (ej. "10.0.0.5")
     */
    public static void eliminarSesionActiva(String ip) {
        String sql = "DELETE FROM sesiones_activas WHERE ip = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ip);

            int filas = ps.executeUpdate();
            
            if (filas > 0) {
                System.out.println("[SesionesDB] Logout exitoso. Sesión eliminada para IP: " + ip);
            } else {
                System.out.println("[SesionesDB] Intento de Logout para IP " + ip + 
                                   ", pero no existía en sesiones_activas.");
            }

        } catch (SQLException e) {
            System.err.println("[SesionesDB] ERROR al eliminar sesión activa para IP: " + ip);
            e.printStackTrace();
        }
    }

}
