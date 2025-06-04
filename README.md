# Online Pong 

## Overview

This project implements a networked Pong game playable:

* **On desktop (Java Swing client)** over plain TCP (port 12345).
* **In any modern browser or mobile device** via a web‐based client (HTML/CSS/JS) over secure WebSockets (WSS, port 443).
  Both clients connect to the same Java‐based server (`PongServer`), which manages game state, score, and control messages.

## Features

* Two‐player real‐time Pong (table tennis) with:

  * Keyboard controls: W/S or Up/Down arrows.
  * READY / PAUSE / RESUME / RESTART buttons in both clients.
  * Score to 5 points; ball speed and paddle size adjust over time.
  * “You Win!” / “You Lose!” overlay at match end.
* **Authentication**: simple shared‐secret handshake before joining.
* **Responsive layout** in web client (desktop or mobile).
* Both PC and web clients can play together on the same server.

## Protocols & Ports

1. **TCP (port 12345)**

   * Used by the Java desktop client.
   * Client opens a `Socket(host, 12345)`, expects server to send the single text line `ENTER_SECRET`, then replies with the shared secret.
   * Once authenticated, client and server exchange Java‐serialized objects (`GameState`, `PlayerCommand`, `ControlCommand`) over `ObjectOutputStream`/`ObjectInputStream`.
2. **WebSockets (WSS, port 443)**

   * Used by the browser/mobile client.
   * The HTML/JS client opens `new WebSocket("wss://pong-online.site/ws/")`.
   * Nginx (reverse proxy) terminates TLS and proxies `/ws/` to the Java server’s plain WebSocket listener on `ws://127.0.0.1:8080/`.
   * Over that WS connection, the client first receives the text `ENTER_SECRET`, sends the secret back, then exchanges JSON‐encoded messages representing `PlayerCommand`, `ControlCommand`, and receives `GameState` objects in JSON form.

## Requirements

* **Server (VM or cloud)**

  * Java 17+ (OpenJDK)
  * Nginx
  * Certbot (for Let’s Encrypt certificates)
  * UFW (optional) or OS firewall
  * Google Cloud firewall rule allowing TCP 12345 and ports 80/443
  * A registered domain (e.g. `pong-online.site`) pointing to the server IP
* **Java desktop client (PC)**

  * Java 17+
* **Web client**

  * Any modern browser (desktop or mobile)

## Setup

### 1. DNS & SSL (Let’s Encrypt)

1. In GoDaddy (or your DNS provider), create an **A record**:

   ```
   Host: @
   Points to: 35.234.104.73    # your VM’s public IP
   TTL: 600
   ```

2. (Optional) Create a **CNAME**:

   ```
   Host: www
   Points to: @
   TTL: 600
   ```

3. SSH into your VM:

   ```bash
   sudo apt update
   sudo apt install -y nginx certbot python3-certbot-nginx ufw
   ```

4. Create the webroot:

   ```bash
   sudo mkdir -p /var/www/pong/.well-known/acme-challenge
   sudo chown -R www-data:www-data /var/www/pong
   sudo chmod -R 755 /var/www/pong
   ```

5. Create or edit `/etc/nginx/sites-available/pong-online.conf` as follows:

   ```nginx
   # HTTP (80) – ACME challenges + redirect to HTTPS
   server {
     listen 80;
     listen [::]:80;
     server_name pong-online.site www.pong-online.site;

     root /var/www/pong;
     index index.html;

     location /.well-known/acme-challenge/ {
       try_files $uri =404;
     }
     location / {
       return 301 https://$host$request_uri;
     }
   }

   # HTTPS (443) – serve static & proxy WebSocket at /ws/
   server {
     listen 443 ssl http2;
     listen [::]:443 ssl http2;
     server_name pong-online.site www.pong-online.site;

     root /var/www/pong;
     index index.html;

     ssl_certificate     /etc/letsencrypt/live/pong-online.site/fullchain.pem;
     ssl_certificate_key /etc/letsencrypt/live/pong-online.site/privkey.pem;
     include             /etc/letsencrypt/options-ssl-nginx.conf;
     ssl_dhparam         /etc/letsencrypt/ssl-dhparams.pem;

     location / {
       try_files $uri $uri/ =404;
     }
     location /ws/ {
       proxy_pass          http://127.0.0.1:8080/;
       proxy_http_version  1.1;
       proxy_set_header    Upgrade $http_upgrade;
       proxy_set_header    Connection "Upgrade";
       proxy_set_header    Host $host;
       proxy_read_timeout  86400s;
       proxy_send_timeout  86400s;
     }
   }
   ```

6. Enable this site and disable the default:

   ```bash
   sudo ln -sf /etc/nginx/sites-available/pong-online.conf /etc/nginx/sites-enabled/
   sudo rm -f /etc/nginx/sites-enabled/default
   sudo nginx -t
   sudo systemctl reload nginx
   ```

7. Obtain a certificate:

   ```bash
   sudo certbot certonly \
     --webroot -w /var/www/pong \
     -d pong-online.site -d www.pong-online.site
   ```

8. Reload Nginx again:

   ```bash
   sudo nginx -t
   sudo systemctl reload nginx
   ```

### 2. Firewall Rules

1. **Allow UFW (optional)**:

   ```bash
   sudo ufw allow SSH
   sudo ufw allow http
   sudo ufw allow https
   sudo ufw allow 12345/tcp
   sudo ufw enable
   sudo ufw status
   ```
2. **Open GCP firewall (done via console or CLI)**:

   * In GCP Console → VPC Network → Firewall rules, create a rule:

     * Name: `allow-pong-12345`
     * Network: `default`
     * Targets: `All instances in the network`
     * Source IP ranges: `0.0.0.0/0`
     * Protocols and ports: `tcp:12345`
   * Or via `gcloud`:

     ```bash
     gcloud compute firewall-rules create allow-pong-12345 \
       --direction=INGRESS \
       --network=default \
       --action=ALLOW \
       --rules=tcp:12345 \
       --source-ranges=0.0.0.0/0
     ```

Verify from your laptop/PC:

```bash
nc -vz pong-online.site 12345
# ⇒ Connection to pong-online.site 12345 port [tcp/*] succeeded!
```

---

## 3. Server (PongServer.java)

1. **Set the shared‐secret** environment variable:

   ```bash
   export PONG_SECRET="secret123"
   ```
2. **Compile** (on the VM; assumes Java 17+ is installed):

   ```bash
   cd ~/Online_PingPong
   javac -cp "libs/java-websocket-1.5.3.jar:libs/slf4j-api-1.7.36.jar:\
   ```

libs/slf4j-simple-1.7.36.jar\:libs/gson-2.8.9.jar:." \*.java

```
3. **Run**:
```bash
java -cp "libs/java-websocket-1.5.3.jar:libs/slf4j-api-1.7.36.jar:\
libs/slf4j-simple-1.7.36.jar:libs/gson-2.8.9.jar:." PongServer
```

You should see:

```
>> WebSocketServer listening on port 8080
>> TCP Server listening on port 12345
WebSocket server started.
```

* TCP on 12345 for Java desktop clients.
* WebSocket on 8080 (proxied via Nginx on `/ws/`) for browsers.

---

## 4. Java Desktop Client

Place these four files together on your PC:

1. **GameState.java**
2. **PlayerCommand.java**
3. **ControlCommand.java**
4. **PongClient.java**

### Compile

```bash
cd /path/to/DesktopClient
javac GameState.java PlayerCommand.java ControlCommand.java PongClient.java
```

### Run

```bash
java PongClient
```

* When prompted:

  1. **Server hostname:** `pong-online.site`
  2. **Server port:** `12345`
  3. **Shared secret:** `secret123`  (must match `PONG_SECRET` on server)
  4. **Player number:** `1` or `2`

A Swing window (1600×960 default) appears. Use W/S or Up/Down to move your paddle. Click **READY** to begin. The server console logs:

```
TCP Player 1 authenticated.
Player 1 is ready.
…
```

---

## 5. Web Client (HTML/CSS/JS)

Your web client lives on GitHub Pages at:

```
https://cukowski.github.io/Online_PingPong/index.html
```

### Files

* **index.html** (UI container)
* **styles.css** (responsive styling)
* **script.js** (WebSocket logic, rendering via Canvas)

#### Key portions of script.js

* **Protocol**: browser opens WSS to `wss://pong-online.site/ws/`.
* Nginx decrypts TLS and proxies to `ws://127.0.0.1:8080/`.
* After handshake (`ENTER_SECRET` → send secret), the client sends JSON‐encoded `{ type: 'CHOOSE_PLAYER', player: 1 }`, then READY, MOVE, CONTROL messages.
* Renders the game on a `<canvas>` that automatically resizes to fit both desktop and mobile screens.

#### GitHub Pages Setup

* Push `index.html`, `styles.css`, and `script.js` to the `gh-pages` (or `main` with Pages enabled) branch of your repo.
* After \~1 minute, verify:

  ```
  https://cukowski.github.io/Online_PingPong/index.html
  ```
* When you visit, DevTools Console should show:

  ```
  connectWebSocket() called
  Opening WebSocket to wss://pong-online.site/ws/
  WebSocket opened to wss://pong-online.site/ws/, waiting for server prompt...
  Received WebSocket message: ENTER_SECRET
  ```
* Enter the same shared secret (`secret123`), choose player 1 or 2, click READY to start.

---

## 6. How It All Fits Together

1. **Server (`PongServer`)**

   * Listens on **TCP 12345** for Java desktop clients.
   * Listens on **WS 8080** for WebSocket clients (proxied to `wss://…/ws/` by Nginx).
   * Performs a text‐based secret handshake, then exchanges Java objects for TCP clients or JSON messages for WebSocket clients.
   * Maintains shared `GameState`, updates physics at \~60 Hz, and broadcasts to both TCP and WS clients.
2. **Java Swing Client**

   * Connects to `pong-online.site:12345` → reads `ENTER_SECRET`, sends secret, wraps streams → receives `GameState` objects.
   * Renders a 16:9 canvas that scales to any window size, draws paddles, ball, scores, pause, and game‐over screens.
   * Sends `PlayerCommand` (−1/0/1) on key events, and `ControlCommand` (READY/PAUSE/RESUME/RESTART) on button clicks.
3. **Web Client (HTML/JS)**

   * Connects to `wss://pong-online.site/ws/` (because the page is served over HTTPS).
   * Nginx proxies that TLS‐wrapped connection to `ws://127.0.0.1:8080/` (no TLS on the Java server).
   * After text handshake, exchanges JSON messages:

     * `{ type: 'CHOOSE_PLAYER', player: 1 }`
     * `{ type: 'READY' }`, `{ type: 'PAUSE' }`, `{ type: 'RESUME' }`, `{ type: 'RESTART' }`
     * `{ type: 'MOVE', direction: -1/0/1 }`
   * Receives `{ type: 'STATE', … }` updates at \~60 Hz containing ball/paddles/scores, and renders them on a responsive `<canvas>`.
4. **Firewall**

   * Google Cloud Firewall rule `allow-pong-12345 (tcp:12345)` → lets external laptops connect to the Java server.
   * Nginx already allows ports 80/443.

By following these instructions, you now have a **fully‐functional** multi‐platform Pong game:

* **Desktop Java clients** connect via TCP (12345).
* **Browser/mobile clients** connect via secure WebSocket (`wss://pong-online.site/ws/`).
* Both can play together in the same match.
