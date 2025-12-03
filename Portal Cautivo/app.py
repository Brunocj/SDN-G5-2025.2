from flask import Flask, request, render_template, jsonify, session, redirect, url_for
import pyrad.packet
from pyrad.client import Client
from pyrad.dictionary import Dictionary
import requests
import mysql.connector
import hashlib
import os
import re

app = Flask(__name__)
app.secret_key = 'super_secreto_universidad' 

# ============================================================
# CONFIGURACIÓN
# ============================================================

# -------------------- RADIUS --------------------
RADIUS_SERVER = "127.0.0.1"
RADIUS_SECRET = b"testing123"
DICT_PATH = "/home/ubuntu/portal_cautivo/dictionary"

DB_RADIUS = {
    'user': 'radius',
    'password': 'ubuntu',
    'host': '127.0.0.1',
    'database': 'radius'
}

# -------------------- SDN POLICY DB --------------------
DB_SDN = {
    'user': 'dti_dodo',
    'password': 'ubuntu',  
    'host': '127.0.0.1',
    'database': 'sdn_policy'
}

# -------------------- FLOODLIGHT --------------------
IP_FLOODLIGHT = "192.168.201.200"
PORT_FLOODLIGHT = "8080"
SWITCH_ID = "00:00:00:00:00:00:00:01"

# MAPEO VISUAL DE ROLES
ROLES_VISUALES = {
    "ROLE_ADMIN": "DTI",
    "ROLE_RESEARCHER": "INVESTIGADOR",
    "ROLE_PROFESSOR": "PROFESOR",
    "ROLE_STUDENT": "ALUMNO",
    "ROLE_GUEST": "INVITADO"
}

# ============================================================
# CACHE
# ============================================================
@app.after_request
def add_header(response):
    response.headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    response.headers['Pragma'] = 'no-cache'
    response.headers['Expires'] = '-1'
    return response

# ============================================================
# RUTAS API
# ============================================================
@app.route('/api/roles/<username>', methods=['GET'])
def api_roles(username):
    roles = obtener_roles_sdn(username)
    return jsonify({"status": "success", "username": username, "roles": roles,
                    "default_role": determining_rol_principal(roles)})

# ============================================================
# PORTAL PRINCIPAL
# ============================================================
@app.route('/', methods=['GET', 'POST'])
def index():
    error = None

    # 1) Primero manejamos los POST especiales (crear usuario)
    if request.method == 'POST':
        action = request.form.get('action')

        # --- ADMIN creando usuario ---
        if action == 'crear_usuario':
            # Solo permitir si hay usuario logueado y es ADMIN
            if 'user_logged_in' not in session or session.get('role_active') != 'ROLE_ADMIN':
                return redirect(url_for('index'))

            # Aquí sí llamamos a crear_usuario_bd (o procesar_creacion_usuario si usas esa)
            msg, tipo = crear_usuario_bd(request)
            session['flash_msg'] = msg
            session['flash_type'] = tipo
            return redirect(url_for('index'))

        # --- Login normal (no tiene 'action' o es otra cosa) ---
        if 'username' in request.form:
            username = request.form['username']
            password = request.form['password']
            user_ip = request.remote_addr

            if autenticar_con_radius(username, password):
                roles = obtener_roles_sdn(username)
                if len(roles) > 1:
                    session['temp_user'] = username
                    session['temp_roles'] = roles
                    session['temp_ip'] = user_ip
                    return render_template('selector.html', username=username, roles=roles)
                else:
                    return finalizar_login(username, user_ip, roles[0])
            else:
                error = "Credenciales incorrectas."

    # 2) Si ya está autenticado (GET o POST que no era crear_usuario)
    if 'user_logged_in' in session:
        msg = session.pop('flash_msg', None)
        msg_type = session.pop('flash_type', 'success')
        return render_pantalla_conectado(
            session['user_logged_in'],
            session['role_active'],
            session.get('user_ip'),
            msg,
            msg_type
        )

    # 3) Página de login por defecto
    return render_template('login.html', error=error)



# ============================================================
# POST → Selección de Rol (si tiene varios)
# ============================================================
@app.route('/seleccionar_rol', methods=['POST'])
def seleccionar_rol():
    rol = request.form.get('rol_seleccionado')
    user = session.get('temp_user')
    ip = session.get('temp_ip')

    if not user or rol not in session.get('temp_roles', []):
        return redirect(url_for('index'))

    return finalizar_login(user, ip, rol)

# ============================================================
# LOGOUT
# ============================================================
@app.route('/logout', methods=['POST'])
def logout():
    u = session.get('user_logged_in')
    ip = session.get('user_ip')

    if u and ip:
        revocar_en_floodlight(ip)
        enviar_accounting_stop(u, ip)

    session.clear()
    return redirect(url_for('index'))

# ============================================================
# LÓGICA DE LOGIN COMPLETO
# ============================================================
def finalizar_login(username, ip, rol):
    # 1. Registro de inicio de sesión en RADIUS (Accounting)
    enviar_accounting_start(username, ip)

    # 2. Obtener las políticas específicas desde la BD (Tu nueva función)
    # Nota: 'rol' es el que el usuario seleccionó o el que tenía por defecto
    recursos_autorizados = obtener_recursos_autorizados_por_rol(username, rol)
    
    # 3. Enviar todo a Floodlight
    # Intentamos aplicar las políticas ACL complejas
    print(f"[SDN] Aplicando políticas para {username} ({ip}) con rol {rol}: {recursos_autorizados}")
    exito_acl = enviar_politicas_a_floodlight(ip, recursos_autorizados)
    
    # Opcional: Mantener tu antigua llamada 'autorizar_en_floodlight' si hacía otra cosa 
    # (como el etiquetado básico en Tabla 0), o integrarla aquí.
    # Por ahora asumiremos que el nuevo endpoint hace todo el trabajo.
    
    if exito_acl:
        # 4. Crear sesión local en Flask
        session['user_logged_in'] = username
        session['role_active'] = rol
        session['user_ip'] = ip
        
        # Limpiar variables temporales
        session.pop('temp_user', None)
        session.pop('temp_roles', None)
        session.pop('temp_ip', None)
        
        return redirect(url_for('index'))
    else:
        # Si falla el controlador, decides si bloqueas al usuario o lo dejas pasar con permisos mínimos
        # Para seguridad estricta: retornamos error.
        revocar_en_floodlight(ip) # Por si acaso
        enviar_accounting_stop(username, ip)
        return render_template('login.html', error="Error aplicando políticas de red. Contacte a DTI.")


# ============================================================
# INTERFAZ PRINCIPAL (Dashboard)
# ============================================================
def render_pantalla_conectado(username, rol, ip, msg=None, mtype="success"):
    rol_visual = ROLES_VISUALES.get(rol, rol)

    # ===================== PANEL ADMIN (opcional) =====================
    html_admin = ""
    if rol == "ROLE_ADMIN":
        users = obtener_todos_los_usuarios()

        rows = ""
        for u in users:
            roles_trad = [ROLES_VISUALES.get(r, r) for r in u['r']]
            roles_str = ", ".join(roles_trad)
            rows += f"""
                <tr>
                    <td class="cell-user">{u['u']}</td>
                    <td class="cell-role">{roles_str}</td>
                </tr>
            """

        html_admin = f"""
        <section class="card admin-card">
            <h3 class="card-title">👑 Panel de Administración</h3>

            <div class="card-subsection">
                <h4 class="sub-title">Nuevo usuario</h4>
                <form method="POST" class="form-grid">
                    <input type="hidden" name="action" value="crear_usuario">

                    <label class="form-label">
                        Correo institucional
                        <input type="email" name="new_username" placeholder="usuario@uni.edu" required>
                    </label>

                    <label class="form-label">
                        Contraseña inicial
                        <input type="text" name="new_password" placeholder="••••••" required>
                    </label>

                    <div class="roles-group">
                        <span class="roles-title">Roles:</span>
                        <label><input type="checkbox" name="new_roles" value="ROLE_STUDENT"> Alumno</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_PROFESSOR"> Profesor</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_RESEARCHER"> Investigador</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_ADMIN"> Admin</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_GUEST"> Invitado</label>
                    </div>

                    <button type="submit" class="btn btn-primary">Crear usuario</button>
                </form>
            </div>

            <div class="card-subsection">
                <h4 class="sub-title">Usuarios registrados</h4>
                <div class="table-wrapper">
                    <table class="users-table">
                        <thead>
                            <tr>
                                <th>Usuario</th>
                                <th>Roles</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows}
                        </tbody>
                    </table>
                </div>
            </div>
        </section>
        """

    # ===================== FEEDBACK (opcional) =====================
    feedback_html = ""
    if msg:
        css_class = "alert-success" if mtype == "success" else "alert-error"
        feedback_html = f"""
        <div class="alert {css_class}">
            {msg}
        </div>
        """

    # ===================== LAYOUT PRINCIPAL =====================
    return f"""
    <!DOCTYPE html>
    <html lang="es">
    <head>
        <meta charset="UTF-8">
        <title>Portal SDN - Conectado</title>
        <style>
            body {{
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                margin: 0;
                padding: 0;
                background: #f5f6fa;
            }}
            .page-container {{
                max-width: 900px;
                margin: 30px auto;
                padding: 0 16px 40px 16px;
            }}
            .card {{
                background: #ffffff;
                border-radius: 12px;
                padding: 24px 24px 20px 24px;
                box-shadow: 0 8px 18px rgba(0,0,0,0.06);
                margin-bottom: 20px;
            }}
            .card-main-title {{
                text-align: center;
                margin-top: 0;
                margin-bottom: 6px;
                font-size: 26px;
                color: #2e7d32;
                letter-spacing: 0.04em;
            }}
            .card-subtitle {{
                text-align: center;
                margin: 0;
                font-size: 18px;
                color: #333;
            }}
            .profile-pill {{
                margin-top: 14px;
                text-align: center;
            }}
            .pill {{
                display: inline-block;
                padding: 6px 18px;
                border-radius: 999px;
                background: #e3f2fd;
                color: #0d47a1;
                font-weight: 600;
                font-size: 14px;
            }}
            .meta-info {{
                margin-top: 8px;
                text-align: center;
                font-size: 12px;
                color: #666;
            }}

            .card-actions {{
                margin-top: 20px;
                text-align: center;
            }}
            .btn {{
                border: none;
                border-radius: 6px;
                padding: 10px 18px;
                font-size: 14px;
                cursor: pointer;
                font-weight: 600;
            }}
            .btn-logout {{
                background: #d32f2f;
                color: #fff;
                width: 200px;
            }}
            .btn-logout:hover {{
                background: #b71c1c;
            }}

            /* Admin panel */
            .admin-card {{
                margin-top: 12px;
            }}
            .card-title {{
                margin-top: 0;
                margin-bottom: 10px;
                font-size: 20px;
                color: #263238;
            }}
            .card-subsection {{
                margin-top: 12px;
                padding-top: 10px;
                border-top: 1px solid #eceff1;
            }}
            .sub-title {{
                margin: 0 0 10px 0;
                font-size: 15px;
                color: #455a64;
                text-transform: uppercase;
                letter-spacing: 0.06em;
            }}
            .form-grid {{
                display: grid;
                grid-template-columns: 1fr 1fr;
                grid-gap: 10px 16px;
                align-items: flex-start;
            }}
            .form-label {{
                font-size: 13px;
                color: #455a64;
                display: flex;
                flex-direction: column;
                gap: 4px;
            }}
            .form-label input[type="email"],
            .form-label input[type="text"] {{
                padding: 6px 8px;
                font-size: 13px;
                border-radius: 4px;
                border: 1px solid #cfd8dc;
            }}
            .roles-group {{
                grid-column: 1 / -1;
                display: flex;
                flex-wrap: wrap;
                gap: 6px 16px;
                font-size: 13px;
                padding: 8px 10px;
                background: #f5f5f5;
                border-radius: 6px;
            }}
            .roles-title {{
                font-weight: 600;
                margin-right: 6px;
            }}
            .btn-primary {{
                grid-column: 1 / -1;
                justify-self: flex-start;
                background: #1976d2;
                color: #fff;
                padding: 8px 18px;
            }}
            .btn-primary:hover {{
                background: #0d47a1;
            }}

            .table-wrapper {{
                max-height: 240px;
                overflow-y: auto;
                border-radius: 8px;
                border: 1px solid #eceff1;
                background: #fafafa;
            }}
            .users-table {{
                width: 100%;
                border-collapse: collapse;
                font-size: 13px;
            }}
            .users-table thead {{
                position: sticky;
                top: 0;
                background: #eceff1;
                z-index: 1;
            }}
            .users-table th,
            .users-table td {{
                padding: 6px 10px;
                text-align: left;
                border-bottom: 1px solid #e0e0e0;
            }}
            .cell-user {{
                font-weight: 600;
                color: #263238;
            }}
            .cell-role {{
                color: #546e7a;
            }}

            /* Alerts */
            .alert {{
                margin-top: 14px;
                padding: 10px 14px;
                border-radius: 6px;
                font-size: 13px;
                text-align: center;
            }}
            .alert-success {{
                background: #d4edda;
                color: #155724;
                border: 1px solid #c3e6cb;
            }}
            .alert-error {{
                background: #f8d7da;
                color: #721c24;
                border: 1px solid #f5c6cb;
            }}

            @media (max-width: 720px) {{
                .form-grid {{
                    grid-template-columns: 1fr;
                }}
                .page-container {{
                    margin-top: 16px;
                }}
            }}
        </style>
    </head>
    <body>
        <div class="page-container">

            <section class="card">
                <h1 class="card-main-title">¡CONECTADO!</h1>
                <h2 class="card-subtitle">Hola, {username}</h2>

                <div class="profile-pill">
                    <span class="pill">Perfil activo: <b>{rol_visual}</b></span>
                </div>

                <div class="meta-info">
                    IP del usuario: <code>{ip}</code>
                </div>

                {feedback_html}

                <div class="card-actions">
                    <form action="/logout" method="POST">
                        <button type="submit" class="btn btn-logout">Cerrar sesión</button>
                    </form>
                </div>
            </section>

            {html_admin}

        </div>
    </body>
    </html>
    """


# ============================================================
#   FUNCIONES DE BD → SDN_POLICY
# ============================================================
def obtener_roles_sdn(username):
    roles = []
    try:
        conn = mysql.connector.connect(**DB_SDN)
        cur = conn.cursor()
        cur.execute("""
            SELECT r.role_name
            FROM user_roles ur
            JOIN users u ON ur.user_id = u.id
            JOIN roles r ON ur.role_id = r.id
            WHERE u.username = %s
        """, (username,))
        for (r,) in cur.fetchall():
            roles.append(r)
        conn.close()
    except Exception as e:
        print("ERROR obtener_roles_sdn:", e)

    return roles if roles else ["ROLE_GUEST"]


def crear_usuario_bd(req):
    username = req.form['new_username'].strip()
    password = req.form['new_password']
    roles = req.form.getlist('new_roles')

    if not roles:
        return "Debes seleccionar al menos un rol.", "error"

    try:
        # ---------------- RADIUS ----------------
        conn_r = mysql.connector.connect(**DB_RADIUS)
        cur_r = conn_r.cursor()
        pass_hash = hashlib.sha1(password.encode()).hexdigest()
        cur_r.execute("""
            INSERT INTO radcheck (username, attribute, op, value)
            VALUES (%s, 'SHA-Password', ':=', %s)
        """, (username, pass_hash))
        conn_r.commit()
        conn_r.close()

        # ---------------- SDN_POLICY ----------------
        conn = mysql.connector.connect(**DB_SDN)
        cur = conn.cursor()

        # OJO: usamos fullname='', lastname='' y career_id=1 como default
        cur.execute("""
            INSERT INTO users (username, fullname, lastname, email, career_id)
            VALUES (%s, %s, %s, %s, %s)
        """, (username, '', '', username, 1))

        user_id = cur.lastrowid

        # Insertar roles
        for role_name in roles:
            cur.execute("SELECT id FROM roles WHERE role_name=%s", (role_name,))
            r = cur.fetchone()
            if r:
                role_id = r[0]
                cur.execute("""
                    INSERT INTO user_roles (user_id, role_id)
                    VALUES (%s, %s)
                """, (user_id, role_id))

        conn.commit()
        conn.close()

        return f"Usuario {username} creado correctamente.", "success"

    except Exception as e:
        print("ERROR crear_usuario_bd:", e)  # ← para que salga en consola
        return f"Error creando usuario: {e}", "error"



def obtener_todos_los_usuarios():
    try:
        conn = mysql.connector.connect(**DB_SDN)
        cur = conn.cursor()
        cur.execute("""
            SELECT u.username, r.role_name
            FROM user_roles ur
            JOIN users u ON ur.user_id = u.id
            JOIN roles r ON ur.role_id = r.id
            ORDER BY u.username
        """)
        rows = cur.fetchall()
        conn.close()

        usuarios = {}
        for u, role in rows:
            if u not in usuarios:
                usuarios[u] = []
            usuarios[u].append(role)

        return [{'u':u, 'r':roles} for u,roles in usuarios.items()]

    except Exception as e:
        print("ERROR obtener_todos:", e)
        return []
        
        
        
def obtener_recursos_autorizados_por_rol(username, radius_role):
    """
    Retorna una lista de diccionarios únicos con los recursos permitidos.
    Ejemplo: [{'ip': '10.0.0.80', 'port': 80, 'proto': 'tcp'}, ...]
    """
    recursos_unicos = []
    
    try:
        conn = mysql.connector.connect(**DB_SDN)
        cur = conn.cursor()

        query = """
            SELECT res.server_ip, res.server_port, res.protocol
            FROM resources res
            JOIN resources_roles rr ON res.id = rr.resource_id
            JOIN roles r ON rr.role_id = r.id
            WHERE r.role_name = %s
        """
        
        # Ejecutamos pasando los parámetros en orden: (rol, usuario, usuario)
        cur.execute(query, (radius_role,))
        
        for (ip, port, proto) in cur.fetchall():
            # Convertimos 'any' a None para que sea fácil de procesar luego
            protocolo_final = proto if proto != 'any' else None
            
            recursos_unicos.append({
                "ip": ip,
                "port": port,
                "protocol": protocolo_final
            })
            
        conn.close()
        
    except Exception as e:
        print(f"Error consultando recursos SDN: {e}")
        
    return recursos_unicos



def enviar_politicas_a_floodlight(user_ip, lista_recursos):
    # Endpoint que definiremos en Java más adelante
    url = f"http://{IP_FLOODLIGHT}:{PORT_FLOODLIGHT}/wm/resources/push"
    
    # Construimos el payload JSON final
    payload = {
        "user_ip": user_ip,
        "resources": lista_recursos  # Es la lista de dicts que devuelve tu nueva función
    }
    
    try:
        # Enviamos POST al controlador con un timeout corto (para no colgar el portal)
        resp = requests.post(url, json=payload, timeout=2)
        
        if resp.status_code == 200:
            print(f"[SDN] Políticas aplicadas para {user_ip}")
            return True
        else:
            print(f"[SDN] Error del controlador: {resp.status_code} - {resp.text}")
            return False
            
    except requests.exceptions.RequestException as e:
        print(f"[SDN] Fallo de conexión con Floodlight: {e}")
        return False



# ============================================================
#   AUTENTICACIÓN CON RADIUS
# ============================================================
def autenticar_con_radius(u, p):
    try:
        srv = Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req = srv.CreateAuthPacket(code=pyrad.packet.AccessRequest, User_Name=u)
        req["User-Password"] = req.PwCrypt(p)
        return srv.SendPacket(req).code == pyrad.packet.AccessAccept
    except:
        return False


def enviar_accounting_start(u, ip):
    try:
        srv = Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req = srv.CreateAcctPacket(User_Name=u)
        req["Acct-Status-Type"] = "Start"
        req["Framed-IP-Address"] = ip
        req["Acct-Session-Id"] = f"{u}-{ip}"
        srv.SendPacket(req)
    except:
        pass

def enviar_accounting_stop(u, ip):
    try:
        srv = Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req = srv.CreateAcctPacket(User_Name=u)
        req["Acct-Status-Type"] = "Stop"
        req["Framed-IP-Address"] = ip
        req["Acct-Session-Id"] = f"{u}-{ip}"
        srv.SendPacket(req)
    except:
        pass


# ============================================================
#   INTEGRACIÓN CON FLOODLIGHT
# ============================================================
def obtener_mac_de_ip(ip):
    try:
        s = os.popen(f'arp -n {ip}')
        m = re.search(r"(([a-f\d]{1,2}\:){5}[a-f\d]{1,2})", s.read())
        if m: return m.group(0)
    except:
        pass
    return None


def autorizar_en_floodlight(ip, role):
    mac = obtener_mac_de_ip(ip)
    url = f"http://{IP_FLOODLIGHT}:{PORT_FLOODLIGHT}/wm/staticflowpusher/json"

    prio = "32500" if role in ["ROLE_PROFESSOR", "ROLE_RESEARCHER", "ROLE_ADMIN"] else "32000"

    data = {
        "switch": SWITCH_ID,
        "name": f"auth_{ip}",
        "priority": prio,
        "eth_type": "0x0800",
        "ipv4_src": ip,
        "active": "true",
        "actions": "output=normal"
    }

    if mac:
        data["eth_src"] = mac

    try:
        requests.post(url, json=data, timeout=2)
        return True
    except:
        return True


def revocar_en_floodlight(ip):
    try:
        requests.delete(
            f"http://{IP_FLOODLIGHT}:{PORT_FLOODLIGHT}/wm/staticflowpusher/json",
            json={"name": f"auth_{ip}"}, timeout=2
        )
    except:
        pass


# ============================================================
# MAIN
# ============================================================
if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80, debug=True)
