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

# ================= CONFIGURACIÓN =================
RADIUS_SERVER = "127.0.0.1"
RADIUS_SECRET = b"testing123"
DICT_PATH = "/home/ubuntu/portal_cautivo/dictionary"

IP_FLOODLIGHT = "192.168.201.200" 
PORT_FLOODLIGHT = "8080"
SWITCH_ID = "00:00:00:00:00:00:00:01"

DB_CONFIG = {
    'user': 'radius', 'password': 'ubuntu', 'host': '127.0.0.1', 'database': 'radius'
}

# MAPEO DE ROLES (Técnico -> Visual)
ROLES_VISUALES = {
    "ROLE_ADMIN": "DTI",
    "ROLE_RESEARCHER": "INVESTIGADOR",
    "ROLE_PROFESSOR": "PROFESOR",
    "ROLE_STUDENT": "ALUMNO",
    "ROLE_GUEST": "INVITADO"
}

# ================= CACHÉ =================
@app.after_request
def add_header(response):
    response.headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    response.headers['Pragma'] = 'no-cache'
    response.headers['Expires'] = '-1'
    return response

# ================= RUTAS API =================
@app.route('/api/roles/<username>', methods=['GET'])
def api_roles(username):
    roles = obtener_roles_db(username)
    return jsonify({"status": "success", "username": username, "roles": roles, "default_role": determining_rol_principal(roles)})

@app.route('/getRolesConCorreo', methods=['GET'])
def get_roles_con_correo():
    correo = request.args.get('correo')
    if not correo: return jsonify({"error": "Falta parametro correo"}), 400
    roles = obtener_roles_db(correo)
    return jsonify({"status": "success", "correo": correo, "roles": roles})

# ================= RUTAS WEB =================

@app.route('/', methods=['GET', 'POST'])
def index():
    if 'user_logged_in' in session:
        msg = session.pop('flash_msg', None)
        msg_type = session.pop('flash_type', 'success')
        return render_pantalla_conectado(session['user_logged_in'], session['role_active'], session.get('user_ip'), msg, msg_type)

    error = None

    if request.method == 'POST':
        action = request.form.get('action')

        if action == 'crear_usuario':
            if 'user_logged_in' not in session: return redirect(url_for('index'))
            msg, tipo = procesar_creacion_usuario(request)
            session['flash_msg'] = msg
            session['flash_type'] = tipo
            return redirect(url_for('index'))

        elif 'username' in request.form:
            username = request.form['username']
            password = request.form['password']
            user_ip = request.remote_addr

            if autenticar_con_radius(username, password):
                roles = obtener_roles_db(username)
                if len(roles) > 1:
                    session['temp_user'] = username
                    session['temp_roles'] = roles
                    session['temp_ip'] = user_ip
                    return render_template('selector.html', username=username, roles=roles)
                else:
                    return finalizar_login(username, user_ip, roles[0])
            else:
                error = "Credenciales incorrectas."

    return render_template('login.html', error=error)

@app.route('/seleccionar_rol', methods=['POST'])
def seleccionar_rol():
    rol = request.form.get('rol_seleccionado')
    user = session.get('temp_user')
    ip = session.get('temp_ip')
    
    if not user or rol not in session.get('temp_roles', []):
        return redirect(url_for('index'))
        
    return finalizar_login(user, ip, rol)

@app.route('/logout', methods=['POST'])
def logout():
    u = session.get('user_logged_in')
    ip = session.get('user_ip')
    if u and ip:
        revocar_en_floodlight(ip)
        enviar_accounting_stop(u, ip)
    session.clear()
    return redirect(url_for('index'))

# ================= LÓGICA =================

def finalizar_login(username, ip, rol):
    enviar_accounting_start(username, ip)
    if autorizar_en_floodlight(ip, rol):
        session['user_logged_in'] = username
        session['role_active'] = rol
        session['user_ip'] = ip
        session.pop('temp_user', None); session.pop('temp_roles', None); session.pop('temp_ip', None)
        return redirect(url_for('index'))
    return render_template('login.html', error="Error SDN.")

def render_pantalla_conectado(username, rol, ip, msg=None, mtype="success"):
    # Traducir rol activo para mostrar
    rol_visual = ROLES_VISUALES.get(rol, rol)
    
    html_admin = ""
    if rol == "ROLE_ADMIN":
        users = obtener_todos_los_usuarios()
        
        # Generar filas traduciendo los roles técnicos a nombres amigables
        rows = ""
        for u in users:
            roles_traducidos = [ROLES_VISUALES.get(r, r) for r in u['r']]
            roles_str = ", ".join(roles_traducidos)
            rows += f"<tr><td style='padding:8px;border-bottom:1px solid #ddd;'>{u['u']}</td><td style='padding:8px;border-bottom:1px solid #ddd;'>{roles_str}</td></tr>"
        
        html_admin = f"""
        <div style='background:#f8f9fa; padding:20px; margin-top:20px; border-radius:10px; text-align:left; border:1px solid #dee2e6;'>
            <h3 style='margin-top:0; color:#495057;'>👑 Panel de Administración</h3>
            
            <div style="background:white; padding:15px; border-radius:5px; border:1px solid #e9ecef; margin-bottom:20px;">
                <h4 style="margin:0 0 10px 0;">Nuevo Usuario</h4>
                <form method="POST">
                    <input type="hidden" name="action" value="crear_usuario">
                    <div style="margin-bottom:10px;">
                        <input type="email" name="new_username" placeholder="Correo (ej: usuario@uni.edu)" required style='width:100%; padding:8px; border:1px solid #ced4da; border-radius:4px;'>
                    </div>
                    <div style="margin-bottom:10px;">
                        <input type="text" name="new_password" placeholder="Contraseña" required style='width:100%; padding:8px; border:1px solid #ced4da; border-radius:4px;'>
                    </div>
                    <div style='margin-top:10px; display:grid; grid-template-columns:1fr 1fr; gap:5px; font-size:0.9em;'>
                        <label><input type="checkbox" name="new_roles" value="ROLE_STUDENT"> Alumno</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_PROFESSOR"> Profesor</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_RESEARCHER"> Investigador</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_ADMIN"> Admin</label>
                        <label><input type="checkbox" name="new_roles" value="ROLE_GUEST"> Invitado</label> <!-- NUEVO CHECKBOX -->
                    </div>
                    <button type="submit" style='width:100%; margin-top:15px; background:#28a745; color:white; padding:10px; border:none; border-radius:5px; cursor:pointer; font-weight:bold;'>Crear Usuario</button>
                </form>
            </div>

            <h4 style="margin-bottom:10px; color:#495057;">Lista de Usuarios ({len(users)})</h4>
            
            <!-- CAJA CON SCROLL (Max Height) -->
            <div style='border:1px solid #ddd; border-radius:5px; max-height: 300px; overflow-y: auto;'>
                <table style='width:100%; border-collapse:collapse; font-size:0.9em; background:white;'>
                    <thead style="position: sticky; top: 0; background: #e9ecef;">
                        <tr><th style='padding:8px; text-align:left;'>Usuario</th><th style='padding:8px; text-align:left;'>Roles</th></tr>
                    </thead>
                    <tbody>
                        {rows}
                    </tbody>
                </table>
            </div>
        </div>
        """
    
    feedback = f"<div style='padding:10px; border-radius:5px; margin-bottom:20px; text-align:center; color:{'#155724' if mtype=='success' else '#721c24'}; background:{'#d4edda' if mtype=='success' else '#f8d7da'}; border:1px solid {'#c3e6cb' if mtype=='success' else '#f5c6cb'};'>{msg}</div>" if msg else ""

    return f"""
    <div style='text-align:center; font-family:sans-serif; margin:30px auto; max-width:600px; padding:30px; border:1px solid #ccc; border-radius:15px; box-shadow:0 0 20px rgba(0,0,0,0.1); background:white;'>
        <h1 style='color:green; margin-top:0;'>¡CONECTADO!</h1>
        <h2 style='color:#333;'>Hola, {username}</h2>
        <div style='background:#e3f2fd; padding:10px 15px; border-radius:50px; display:inline-block; margin-bottom:15px; color:#0d47a1;'>
            Perfil Activo: <b>{rol_visual}</b>
        </div>
        <br>
        {feedback}
        {html_admin}
        <hr style="margin: 20px 0;">
        <form action="/logout" method="POST">
            <button type="submit" style="background:#dc3545; color:white; padding:12px 20px; width:100%; border:none; border-radius:5px; cursor:pointer; font-size:1em; font-weight:bold;">CERRAR SESIÓN</button>
        </form>
    </div>
    """

def procesar_creacion_usuario(req):
    u = req.form['new_username']
    p = req.form['new_password']
    rs = req.form.getlist('new_roles')
    
    if not re.match(r"[^@]+@[^@]+\.[^@]+", u):
        return "ERROR: Formato de correo inválido (ej: juan@uni.edu).", "error"
    if not rs:
        return "ERROR: Debes seleccionar al menos un rol.", "error"
        
    if crear_usuario_bd(u, p, rs):
        return f"Usuario <b>{u}</b> creado correctamente.", "success"
    return "Error creando usuario en DB (¿Posible duplicado?).", "error"

# --- FUNCIONES AUXILIARES ---
def determining_rol_principal(roles):
    order = ["ROLE_ADMIN", "ROLE_PROFESSOR", "ROLE_RESEARCHER", "ROLE_STUDENT", "ROLE_GUEST"]
    for r in order: 
        if r in roles: return r
    return "ROLE_GUEST"

def obtener_roles_db(u):
    l=[]
    try:
        c=mysql.connector.connect(**DB_CONFIG); cur=c.cursor()
        cur.execute("SELECT role_name FROM user_roles WHERE username=%s",(u,))
        for (r,) in cur.fetchall(): l.append(r)
        c.close()
    except: pass
    return l if l else ["ROLE_GUEST"]

def crear_usuario_bd(u, p, rs):
    try:
        c=mysql.connector.connect(**DB_CONFIG); cur=c.cursor()
        ph=hashlib.sha1(p.encode('utf-8')).hexdigest()
        cur.execute("INSERT INTO radcheck (username, attribute, op, value) VALUES (%s, 'SHA-Password', ':=', %s)", (u, ph))
        for r in rs: cur.execute("INSERT INTO user_roles (username, role_name) VALUES (%s, %s)", (u, r))
        grp = determining_rol_principal(rs)
        gm = {"ROLE_GUEST":"grupo_invitado", "ROLE_STUDENT":"grupo_alumno", "ROLE_PROFESSOR":"grupo_profesor", "ROLE_RESEARCHER":"grupo_investigador", "ROLE_ADMIN":"grupo_admin"}
        cur.execute("INSERT INTO radusergroup (username, groupname, priority) VALUES (%s, %s, 1)", (u, gm.get(grp,"grupo_invitado")))
        c.commit(); c.close(); 
        print("--> [DB] Usuario creado exitosamente.")
        return True
    except Exception as e:
        print(f"ERROR DB CREAR: {e}")
        return False

def obtener_todos_los_usuarios():
    d={}
    try:
        c=mysql.connector.connect(**DB_CONFIG); cur=c.cursor()
        cur.execute("SELECT username, role_name FROM user_roles ORDER BY username")
        rows = cur.fetchall()
        print(f"--> [DB DEBUG] Usuarios recuperados: {len(rows)}")
        for u,r in rows: 
            if u not in d: d[u]=[]
            d[u].append(r)
        c.close()
    except Exception as e:
        print(f"ERROR OBTENIENDO USUARIOS: {e}")
        return []
    return [{'u':k,'r':v} for k,v in d.items()]

def autenticar_con_radius(u, p):
    try:
        srv=Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req=srv.CreateAuthPacket(code=pyrad.packet.AccessRequest, User_Name=u)
        req["User-Password"]=req.PwCrypt(p)
        return srv.SendPacket(req).code==pyrad.packet.AccessAccept
    except: return False

def enviar_accounting_start(u, ip):
    try:
        srv=Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req=srv.CreateAcctPacket(User_Name=u); req["Acct-Status-Type"]="Start"; req["Framed-IP-Address"]=ip; req["Acct-Session-Id"]=f"{u}-{ip}"
        srv.SendPacket(req)
    except: pass

def enviar_accounting_stop(u, ip):
    try:
        srv=Client(server=RADIUS_SERVER, secret=RADIUS_SECRET, dict=Dictionary(DICT_PATH))
        req=srv.CreateAcctPacket(User_Name=u); req["Acct-Status-Type"]="Stop"; req["Framed-IP-Address"]=ip; req["Acct-Session-Id"]=f"{u}-{ip}"
        srv.SendPacket(req)
    except: pass

def obtener_mac_de_ip(ip):
    try:
        s=os.popen(f'arp -n {ip}'); m=re.search(r"(([a-f\d]{1,2}\:){5}[a-f\d]{1,2})", s.read())
        if m: return m.group(0)
    except: pass
    return None

def autorizar_en_floodlight(ip, role):
    mac = obtener_mac_de_ip(ip)
    url = f"http://{IP_FLOODLIGHT}:{PORT_FLOODLIGHT}/wm/staticflowpusher/json"
    prio = "32500" if role in ["ROLE_PROFESSOR", "ROLE_RESEARCHER", "ROLE_ADMIN"] else "32000"
    data = {"switch": SWITCH_ID, "name": f"auth_{ip}", "priority": prio, "eth_type": "0x0800", "ipv4_src": ip, "active": "true", "actions": "output=normal"}
    if mac: data["eth_src"] = mac
    try: requests.post(url, json=data, timeout=2); return True
    except: return True

def revocar_en_floodlight(ip):
    try: requests.delete(f"http://{IP_FLOODLIGHT}:{PORT_FLOODLIGHT}/wm/staticflowpusher/json", json={"name": f"auth_{ip}"}, timeout=2)
    except: pass

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80, debug=True)
