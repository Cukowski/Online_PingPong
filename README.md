# Online_PingPong

This repository contains a fully working, cross-platform, multiplayer Pong game. A Java server hosts the game logic and accepts both:

- **Java TCP clients** (desktop applications)  
- **WebSocket clients** (browser-based, HTML/CSS/JavaScript)
- Web Client: [Game Page](https://cukowski.github.io/Online_PingPong/)

Two players (slots 1 and 2) can connect from any combination of desktop and browser. The server handles authentication, “Ready” handshakes, game physics, and broadcasts state to all connected clients. This README walks through **every step** we took to get here—initial design, progressive improvements, detailed implementation notes, and how to compile, run, and test everything.

---

## Table of Contents

1. [Overview & Motivation](#overview--motivation)  
2. [Architecture](#architecture)  
3. [Key Improvements and Iterations](#key-improvements-and-iterations)  
4. [Project Structure](#project-structure)  
5. [Dependencies](#dependencies)  
6. [Setting Up Your Environment](#setting-up-your-environment)  
7. [Compiling the Java Code](#compiling-the-java-code)  
8. [Running the Server](#running-the-server)  
9. [Java TCP Client](#java-tcp-client)  
10. [Browser (WebSocket) Client](#browser-websocket-client)  
11. [Gameplay Workflow](#gameplay-workflow)  
12. [Firewall, Networking, and Deployment Notes](#firewall-networking-and-deployment-notes)  
13. [Troubleshooting & Debugging](#troubleshooting--debugging)  
14. [How to Present to Your Professor](#how-to-present-to-your-professor)  
15. [Contact & License](#contact--license)  

---

## 1. Overview & Motivation

Pong is a classic two-player table-tennis–style arcade game. Our goal was to build a modern, networked version where:

- A single Java server authoritatively runs the game loop and physics.  
- Two clients can connect remotely and play against each other in real time.  
- Clients can be either a **Java desktop application** (using `Socket`, `ObjectInputStream`/`ObjectOutputStream`) or a **browser application** (using WebSockets and a simple HTML5 canvas).  
- We added features such as authentication via a shared secret, a “Ready” handshake to synchronize start, pause/restart buttons, adaptive ball speed, and server-side scoring first to 5.  

Over the course of the project, we went from a simple local Java Pong to a robust, multiplayer server that supports both Java and web clients. This README explains how we did it, step by step.

---

## 2. Architecture

```

+----------------------+             +------------------------+
\|    Java TCP Client   |——┐     ┌—— |     PongServer (TCP)   |
\| (Desktop, Swing GUI) |  └─────┘   |  (Game logic, physics) |
+----------------------+   TCP    +------------------------+
│         │
│ (1)     │ (2)
│         │
▼         ▼
+------------------------+
\|   PongServer (WebSocket)
\|    (JSON STATE, CONTROL)
+------------------------+
▲
│ (3)
│
+---------------------------+
\|   Browser Client (HTML/JS) |
\|   (WebSocket, Canvas)      |
+---------------------------+

Legend:
(1) Java clients connect on TCP port 12345
(2) Server sends/receives PlayerCommand / ControlCommand via Object streams
(3) Browser clients connect on WS port 8080, exchange JSON messages

```

1. **Java TCP Clients**  
   - Each Java client opens a plain TCP `Socket` to the server’s port 12345.  
   - They send/receive **serialized objects**:  
     - `PlayerCommand` (paddle-movement)  
     - `ControlCommand` (READY, PAUSE, RESTART)  
   - The server’s `ClientHandler` (an inner class in `PongServer.java`) handles each TCP client on its own thread.  
   - The server broadcasts `GameState` objects (serialized) to both Java clients.

2. **PongServer (TCP + WebSocket)**  
   - The same Java process opens **two listeners**:  
     - A `ServerSocket` on port 12345 for Java clients.  
     - A `WebSocketServer` on port 8080 (using the [Java-WebSocket library](https://github.com/TooTallNate/Java-WebSocket)) for browser clients.  
   - Both listeners share a single `GameState` instance (paddles, ball, scores, ready flags, paused, winner).  
   - A main game loop (60 Hz) updates physics, processes paddle commands, checks collisions, awards points, and increases difficulty.  
   - After each tick, the server **broadcasts** the updated `GameState` to:  
     - Both connected Java clients (via their `ClientHandler.sendState(state)` method).  
     - All authenticated WebSocket clients (via `broadcastGameState(...)` on the WS server).

3. **Browser (WebSocket) Clients**  
   - HTML page with a `<canvas>` for rendering, CSS for styling, and `script.js` for logic.  
   - When loaded, `script.js` prompts the user for:  
     1. Server **IP/hostname**  
     2. WebSocket **port** (default 8080)  
     3. Shared **secret** (same as `PONG_SECRET` on the server)  
     4. **Player number** (1 or 2)  
   - Once authenticated, the client sends JSON messages:  
     - `{ action: "CHOOSE_PLAYER", p: 1 }` or 2  
     - `{ type: "CONTROL", action: "READY" }` (or PAUSE, RESUME, RESTART)  
     - `{ type: "MOVE", dir: -1/0/1 }` for paddle up/stop/down  
   - On each server broadcast, the client receives `{ type: "STATE", p1Y, p2Y, ballX, ballY, score1, score2, paused, winner, ready1, ready2 }`.  
   - `script.js` parses this JSON, updates local `gameState`, and redraws the canvas at ~60 FPS.

---

## 3. Key Improvements and Iterations

Over the course of development, we made dozens of changes. Below is a chronological summary of the most important steps:

1. **Base Pong in Java (Local Only)**  
   - Started with a simple Swing window, a bouncing ball, and a single paddle.  
   - Added a second paddle, 2-player local mode via keyboard controls (W/S and Up/Down).  
   - Tracked score to 5, displayed winner, and reset ball.

2. **Two-Player TCP Version (Desktop vs. Desktop)**  
   - Created `PlayerCommand` and `ControlCommand` classes, both `Serializable`.  
   - Wrote `PongServer.java` with a `ServerSocket` on 12345.  
   - Two `ClientHandler` threads read incoming commands, set `cmd1` and `cmd2`, run the game loop, and send back a `GameState` object each tick.  
   - Desktop clients (`PongClient.java`) exchanged movement commands over `ObjectOutputStream`/`ObjectInputStream`, displayed same UI as local version.

3. **“Ready” / “Pause” / “Restart” Buttons**  
   - Added `ControlCommand.Type.READY|PAUSE|RESUME|RESTART`.  
   - On the server, `state.ready1` and `state.ready2` remain false until both clients send a READY.  
   - The server’s main loop blocked in `while (!ready1 || !ready2)` before starting play.  
   - Added `PAUSE` and `RESUME` support so either player could pause mid-game.  
   - Added `RESTART` that resets `score1`, `score2`, `winner`, clears ready flags, and waits for both to click READY again.

4. **WebSocket (Browser) Client**  
   - Wanted a **browser play option**. Chose [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) to embed a lightweight WS server.  
   - In `PongServer.java`, created an inner class `PongWebSocketServer extends WebSocketServer`. Bound to **0.0.0.0:8080**.  
   - On `onOpen(...)`, server sends the text message `"ENTER_SECRET"`. Client responds with plain secret string. Server validates it and replies `"OK"` or `"FAIL"`.  
   - After `"OK"`, client sends `{"action":"CHOOSE_PLAYER","p":1 or 2}`. Server sets an `attachment` of 1 or 2 on that WebSocket to record slot.  
   - Then every incoming WS message is parsed as JSON:  
     - `{ "type":"MOVE", "dir": -1/0/1 }` → route to `cmd1` or `cmd2`.  
     - `{ "type":"CONTROL", "action":"READY" | "PAUSE" | "RESUME" | "RESTART" }` → sets `state.ready1/2`, `state.paused`, etc.  
   - Each server tick, `broadcastGameState(...)` loops the `Set<WebSocket>` and sends JSON:  
     ```json
     {
       "type":"STATE",
       "p1Y": …,
       "p2Y": …,
       "ballX": …,
       "ballY": …,
       "score1": …,
       "score2": …,
       "paused": …,
       "winner": …,
       "ready1": …,
       "ready2": …
     }
     ```
   - On the **client side**, `script.js` manages:  
     1. Prompting for host, port, secret, player number.  
     2. WebSocket handshake and authentication.  
     3. Sending movement and control commands.  
     4. Receiving and parsing JSON state.  
     5. Drawing on an HTML `<canvas>` at 60 FPS.  
     6. Displaying “Connecting…” / “Waiting for both players to click READY…” / “You Win!” or “You Lose” overlays.

5. **Synchronizing Slot Assignment**  
   - Original versions blocked in `ServerSocket.accept()` until both TCP clients arrived, which hung when both clients were browsers.  
   - We added `serverSocket.setSoTimeout(1000)`, so that every second, `accept()` times out, allowing the main thread to re-check `player1WS` / `player2WS` flags.  
   - Now browser clients can occupy slots 1 and 2 without requiring any TCP client.

6. **Testing & Debugging**  
   - Verified TCP ↔ TCP works (two Java clients).  
   - Verified TCP ↔ WS works (one Java + one browser).  
   - Verified WS ↔ WS works (two browsers).  
   - Logged “Player X is ready.” exactly when each client clicked READY (TCP or WS).  
   - Ensured that “Waiting for both players…” appears in both Java and browser UIs until both ready flags flip to true.  

---

## 4. Project Structure

```

Online\_PingPong/
├── libs/
│   ├── java-websocket-1.5.3.jar
│   ├── slf4j-api-1.7.36.jar
│   ├── slf4j-simple-1.7.36.jar
│   └── gson-2.8.9.jar
│
├── src/                            (optional; all .java files can live in root)
│   ├── PongServer.java
│   ├── PongClient.java
│   ├── GameState.java
│   ├── PlayerCommand.java
│   └── ControlCommand.java
│
├── web/                            (for browser client files)
│   ├── index.html
│   ├── styles.css
│   └── script.js
│
└── README.md

```

- **`libs/`**: Third-party JARs needed at compile time and runtime.  
- **`PongServer.java`**: The single server entry point with both TCP and WebSocket logic.  
- **`PongClient.java`**: Java desktop client (connects over TCP).  
- **`GameState.java`**, **`PlayerCommand.java`**, **`ControlCommand.java`**: Shared `Serializable` classes used by TCP clients and server.  
- **`web/index.html`**, **`web/styles.css`**, **`web/script.js`**: Browser client code.

---

## 5. Dependencies

1. **Java JDK 11+** (we use `java.net` and `java.nio` packages; GSON requires Java 8+).  
2. **Java-WebSocket 1.5.3** (for WS server)  
3. **SLF4J API 1.7.36** + **slf4j-simple 1.7.36** (logging backend used by Java-WebSocket)  
4. **GSON 2.8.9** (for parsing JSON in the server)  
5. **A modern web browser** (Chrome, Firefox, Edge, etc. with WebSocket support).  

Place all JARs into the `libs/` folder before compiling.

---

## 6. Setting Up Your Environment

1. **Clone this repository (or copy files)** into your working directory.  
2. Ensure you have the following in `libs/`:  
   - `java-websocket-1.5.3.jar`  
   - `slf4j-api-1.7.36.jar`  
   - `slf4j-simple-1.7.36.jar`  
   - `gson-2.8.9.jar`  
3. Make sure you have **Java 11+** installed.  

On Linux/Mac:
```bash
java -version
# e.g.: openjdk version "11.0.13" 2021-10-19
````

On Windows, open Command Prompt and run `java -version`.

---

## 7. Compiling the Java Code

From the project root (where `PongServer.java` lives), run:

```bash
# Compile all .java files, including server and client
javac -cp "libs/java-websocket-1.5.3.jar:libs/slf4j-api-1.7.36.jar:libs/slf4j-simple-1.7.36.jar:libs/gson-2.8.9.jar:." \
      *.java
```

* The `-cp` (classpath) flag must include all four JARs **and** the current directory (`.`).
* If your shell does not expand `:` on Windows, replace with `;`, e.g.:

  ```
  javac -cp "libs/java-websocket-1.5.3.jar;libs/slf4j-api-1.7.36.jar;libs/slf4j-simple-1.7.36.jar;libs/gson-2.8.9.jar;." *.java
  ```

Successful compilation will produce `.class` files for each `.java`.

---

## 8. Running the Server

Before starting, you **must** set the environment variable `PONG_SECRET` to some shared password (e.g. `"secret123"`). Clients (Java or browser) will be prompted for this same secret.

```bash
# Unix/macOS:
export PONG_SECRET="secret123"
java -cp "libs/java-websocket-1.5.3.jar:libs/slf4j-api-1.7.36.jar:libs/slf4j-simple-1.7.36.jar:libs/gson-2.8.9.jar:." \
     PongServer
```

On Windows (PowerShell or CMD):

```powershell
set PONG_SECRET=secret123
java -cp "libs/java-websocket-1.5.3.jar;libs/slf4j-api-1.7.36.jar;libs/slf4j-simple-1.7.36.jar;libs/gson-2.8.9.jar;." PongServer
```

You should see:

```
>> WebSocketServer listening on port 8080
>> TCP Server listening on port 12345
WebSocket server started.
```

At this point, the server is up and waiting for two players—either via TCP or WebSocket.

---

## 9. Java TCP Client

The Java client (`PongClient.java`) is a Swing GUI that:

1. Opens a `Socket(host, 12345)`.
2. Reads a line `"ENTER_SECRET\n"` from the server, prompts the user, and sends the secret.
3. Expects `"OK\n"` or `"FAIL\n"` (plain text).
4. Prompts “Enter player number (1 or 2):”.
5. Sends a `ControlCommand(READY)` when the user clicks the READY button.
6. Sends `PlayerCommand(-1|0|1)` whenever the user presses/releases W/S or Up/Down.
7. Receives serialized `GameState` objects from the server, updates the canvas to draw paddles, ball, scores, etc.

### How to Run:

1. Compile it (already done in section 7).
2. Launch the client, specifying the server’s IP (if remote) or `localhost` if on your machine. Example:

   ```bash
   java PongClient
   ```
3. In the Swing window, you’ll be prompted:

   ```
   Enter server IP or hostname:
   ```

   * Type `localhost` or `12.123.123.12` (your server’s external IP).

   ```
   Enter player number (1 or 2):
   ```

   * Type `1` or `2`.
4. Click the `READY` button when you’re ready. The game only starts once both clients (Java or browser) click READY.

---

## 11. Gameplay Workflow

1. **Start the server**

   * Ensure `PONG_SECRET` is set (e.g. `"secret123"`).
   * Launch `java PongServer` (see [Running the Server](#running-the-server)).
   * The console shows both listeners:

     ```
     >> WebSocketServer listening on port 8080
     >> TCP Server listening on port 12345
     WebSocket server started.
     ```

2. **Connect Client 1**

   * **Java TCP client**: `java PongClient`.

     1. Prompt: `Enter server IP/hostname:` → type `localhost` or `<server-ip>`.
     2. Prompt: `Enter player number (1 or 2):` → type `1`.
     3. Prompt: `Enter shared secret:` → type `secret123`.
     4. Ready button appears—click `READY`. The server console logs:

        ```
        Player 1 is ready.
        ```
   * **OR Browser client**: Open `index.html` in a browser.

     1. Prompt for host/port/secret/slot as described above.
     2. Choose slot 1. Click `READY`. The server console logs:

        ```
        WebSocket assigned to Player 1
        Player 1 is ready.
        ```

3. **Connect Client 2**

   * Exactly the same process for slot 2 (either Java or Web).
   * Once both slot-1 and slot-2 clients have clicked `READY`, the server’s `waitForReady()` loop exits and the match begins.

4. **Play Pong (60 FPS)**

   * Java clients use W/S or Up/Down to move paddles.
   * Browser client uses same keys.
   * Ball bounces top/bottom, returns off paddles at random angles.
   * Score increments when the opponent misses. First to 5 wins.

5. **Pause/Resume/Restart**

   * Either player can click `PAUSE` (or send `ControlCommand.RESUME` after paused).
   * Clicking `RESTART` resets score, clears ready flags, and waits for both to click `READY` again.

---

## 12. Firewall, Networking, and Deployment Notes

* **Firewall**:

  * Make sure port 12345 (TCP) and port 8080 (WebSocket) are open and forwarded on your server’s firewall (e.g., GCP, AWS, or local router).
  * On Google Cloud, create a firewall rule to allow inbound TCP on 8080/12345.

* **Binding Addresses**:

  * We explicitly bound the WebSocketServer to `"0.0.0.0"` so it listens on all interfaces.
  * TCP `ServerSocket` also binds to 0.0.0.0 by default.

* **Using ngrok (Optional)**:

  * If you don’t have a public IP, install ngrok on the server:

    ```
    ngrok authtoken <your-ngrok-token>
    ngrok tcp 12345
    ngrok tcp 8080
    ```
  * Ngrok will print a public `tcp://<host>:<port>` endpoint that tunnels to your local server. Use those in the client prompts.

---

## 13. Troubleshooting & Debugging

* **Server never logs “Player X is ready” for a Web client**

  1. Open browser DevTools → Console → Network → WebSocket frames. Ensure the browser sent

     ```json
     { "type":"CONTROL", "action":"READY" }
     ```

     when you clicked the READY button.
  2. In the server’s `onMessage(...)`, confirm that branch is reached and prints `Player X is ready.`
  3. Ensure your WebSocket’s `attachment` is actually an integer 1 or 2 (set by the CHOOSE\_PLAYER step).

* **Server hangs in TCP accept loops when using two browser clients**

  * We added `serverSocket.setSoTimeout(1000)` so that `accept()` times out quickly and re-checks `player1WS`/`player2WS`. Without that, accept would block indefinitely.

* **Canvas remains blank**

  * Check that `gameState` is actually being set (first JSON from server).
  * If you see “Connecting to server…” indefinitely, your WebSocket never opened. Check firewall and correct `host:port`.

* **“Connection refused” or “UnknownHostException”**

  * Verify the server’s IP and port.
  * Confirm the server is running and listening (`netstat -tnlp | grep 8080`).
  * Confirm firewall rules allow inbound.

* **Mismatched ports between Java and JS clients**

  * By default, Java TCP uses port 12345. Browser WS uses port 8080. Always enter the correct port when prompted.

---

## 14. How to Present to Your Professor

When showing this project to your professor, walk through:

1. **Motivation & Scope**

   * Explain why building a multiplayer networked Pong is a good learning exercise (sockets, threads, real-time loops, front-end vs. back-end).

2. **Architecture Diagram**

   * Show the diagram of Java TCP clients ↔ server ↔ WebSocket clients, pointing out shared `GameState`.

3. **Key Classes & Flow**

   * `GameState.java`: Holds paddles, ball, scores, ready flags, paused, winner.
   * `PlayerCommand.java` / `ControlCommand.java`: Simple `Serializable` containers.
   * `PongServer.java`:

     * Section A: TCP accept & `ClientHandler`.
     * Section B: WebSocket server with handshake, JSON parsing, `broadcastGameState()`.
     * Section C: Main game loop (physics, collisions, scoring, difficulty ramp).

4. **Stepwise Improvements**

   * Local single-machine Pong → two wired‐together Java clients → “Ready” handshake → browser client integration → slot assignment tweaks → final robust cross-client design.
   * Show snapshots of intermediate code (e.g. early versions that lacked `ready1/ready2` in JSON) and explain why they failed.

5. **Demonstration**

   * Start the server. Show the console logs: “WebSocket server started. TCP server listening.”
   * Open two browser windows, demonstrate WS handshake, slot assignment, “Player X is ready” logs.
   * Show “Waiting for both players…” message in both browsers.
   * Click READY in each; show console prints, then direct changes in canvases to paddles and ball. Play a short rally.
   * Optionally open a Java client (PongClient), show cross-client interoperability.

6. **Deployment & Networking**

   * If you deployed on GCP or AWS, show the firewall rules → incoming ports → `netstat` confirming listening.
   * Show how you used `ngrok` or GCP firewall to allow external browser connections.

7. **Source Code Walkthrough**

   * Highlight the critical methods:

     * `tryAcceptAndAuthenticate()`
     * `onMessage()` in `PongWebSocketServer`
     * `broadcastGameState(...)` with `ready1/ready2` included
     * `ClientHandler.handleControlTCP(...)`
     * `script.js`’s `gameLoop()` and `connectWebSocket()`

8. **Q\&A / Next Steps**



---

## 15. Contact & License

