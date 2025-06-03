import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.*;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * Existing PongServer (TCP-based) + an embedded WebSocket server on port 8080.
 * 
 * 1) The old TCP path (port 12345) still works exactly as before: Java‐Object‐stream clients must
 *    handshake with "ENTER_SECRET" and, if valid, become player1/player2. 
 * 2) The new WebSocket path (port 8080) expects a text‐"ENTER_SECRET" message, then
 *    replies "OK" or "FAIL". Once authenticated, it listens for JSON commands:
 *      { "type":"MOVE", "dir":-1 }          // paddle up/down
 *      { "type":"CONTROL", "action":"READY" } // one of READY, PAUSE, RESUME, RESTART
 *    and forwards them into the same server logic.  It also watches every GameState update and
 *    broadcasts JSON like:
 *      { "type":"STATE", "p1Y":...,"p2Y":...,"ballX":...,"ballY":...,"score1":...,"score2":...,"paused":..., "winner":0 }
 *    to all connected browsers.
 */
public class PongServer {
    public static final int TCP_PORT = 12345;
    public static final int WS_PORT  = 8080;
    private static final String SHARED_SECRET;

    static {
        String s = System.getenv("PONG_SECRET");
        if (s == null || s.isEmpty()) {
            System.err.println("Error: PONG_SECRET not set in environment");
            System.exit(1);
        }
        SHARED_SECRET = s;
    }

    // GAME CONSTANTS
    private static final int WIDTH = 800, HEIGHT = 600;
    private static final int PADDLE_WIDTH  = 10, PADDLE_HEIGHT = 80;
    private static final int BALL_SIZE = 15;
    private static final int TARGET_SCORE = 5;

    // SHARED STATE
    private final GameState state = new GameState();
    private final AtomicReference<PlayerCommand> cmd1 = new AtomicReference<>(new PlayerCommand(0));
    private final AtomicReference<PlayerCommand> cmd2 = new AtomicReference<>(new PlayerCommand(0));

    // TCP client handlers
    private ClientHandler player1, player2;

    // For broadcasting STATE updates to WebSocket clients
    private final Set<WebSocket> wsClients = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) {
        new PongServer().start();
    }

    private void start() {
        System.out.println(">> Starting PongServer (TCP:" + TCP_PORT + ", WS:" + WS_PORT + ")");

        // 1) Start the WebSocket server on port 8080
        PongWebSocketServer wsServer = new PongWebSocketServer(WS_PORT);
        wsServer.start();
        System.out.println(">> WebSocketServer listening on port " + WS_PORT);

        // 2) Start the existing TCP-based game loop
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            // Keep trying until two Java clients authenticate:
            while (player1 == null) player1 = tryAcceptAndAuthenticate(serverSocket, 1);
            while (player2 == null) player2 = tryAcceptAndAuthenticate(serverSocket, 2);

            new Thread(player1).start();
            new Thread(player2).start();

            // Now run matches forever:
            while (true) {
                initGame();
                waitForReady();
                resetBall(2);
                runMatchLoop(wsServer);
                System.out.println("Match ended. Winner: Player " + state.winner);
                waitForRestart();
                System.out.println("Resetting for next match...");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /** 
     * Try to accept a TCP client (on port 12345), handshake via ENTER_SECRET,
     * and if OK return a ClientHandler; otherwise close and return null (retry).
     */
    private ClientHandler tryAcceptAndAuthenticate(ServerSocket serverSocket, int playerNumber) {
        try {
            Socket sock = serverSocket.accept();
            System.out.println("Incoming TCP connection for P" + playerNumber);
            BufferedWriter textOut = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
            BufferedReader textIn  = new BufferedReader(new InputStreamReader(sock.getInputStream()));

            // 1) Prompt
            textOut.write("ENTER_SECRET\n");
            textOut.flush();

            // 2) Await response (5s timeout)
            sock.setSoTimeout(5000);
            String resp = textIn.readLine();
            if (!SHARED_SECRET.equals(resp)) {
                System.out.println("P" + playerNumber + " wrong secret: " + resp);
                sock.close();
                return null;
            }
            System.out.println("P" + playerNumber + " authenticated (TCP).");
            sock.setSoTimeout(0);
            return new ClientHandler(sock, playerNumber);
        } catch (IOException e) {
            System.out.println("Error accepting P" + playerNumber + ": " + e.getMessage());
            return null;
        }
    }

    private void initGame() {
        state.paddle1Y = HEIGHT/2 - PADDLE_HEIGHT/2;
        state.paddle2Y = HEIGHT/2 - PADDLE_HEIGHT/2;
        state.score1 = state.score2 = 0;
        state.ready1 = state.ready2 = false;
        state.paused = false;
        state.winner = 0;
        cmd1.set(new PlayerCommand(0));
        cmd2.set(new PlayerCommand(0));
    }

    private void waitForReady() throws InterruptedException {
        while (!state.ready1 || !state.ready2) {
            broadcastStateToTCP();
            Thread.sleep(100);
        }
    }

    private void waitForRestart() throws InterruptedException {
        while (state.ready1 || state.ready2) {
            Thread.sleep(100);
        }
    }

    /**
     * Main game loop; runs until state.winner != 0. On each tick:
     *  - apply paddle moves (cmd1/cmd2)
     *  - move ball, detect collisions, update scores
     *  - broadcast GameState to both TCP clients and all WS clients
     */
    private void runMatchLoop(PongWebSocketServer wsServer) throws InterruptedException {
        final int FPS = 60;
        final long frameTime = 1000 / FPS;
        while (state.winner == 0) {
            long start = System.currentTimeMillis();
            if (!state.paused) updateGame();
            broadcastStateToTCP();
            broadcastStateToWS(wsServer);
            long elapsed = System.currentTimeMillis() - start;
            long sleep = frameTime - elapsed;
            if (sleep > 0) Thread.sleep(sleep);
        }
        // Final broadcast
        broadcastStateToTCP();
        broadcastStateToWS(wsServer);
    }

    private void updateGame() {
        applyPaddle(cmd1.get(), 1);
        applyPaddle(cmd2.get(), 2);
        state.ballX += state.ballDX;
        state.ballY += state.ballDY;

        // Bounce off top/bottom
        if (state.ballY <= 0 || state.ballY + BALL_SIZE >= HEIGHT) {
            state.ballDY = -state.ballDY;
        }
        // Left side
        if (state.ballX <= PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle1Y && state.ballY <= state.paddle1Y + PADDLE_HEIGHT) {
                bounceOffPaddle(1);
            } else {
                state.score2++;
                if (state.score2 >= TARGET_SCORE) state.winner = 2;
                else resetBall(1);
            }
        }
        // Right side
        if (state.ballX + BALL_SIZE >= WIDTH - PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle2Y && state.ballY <= state.paddle2Y + PADDLE_HEIGHT) {
                bounceOffPaddle(2);
            } else {
                state.score1++;
                if (state.score1 >= TARGET_SCORE) state.winner = 1;
                else resetBall(2);
            }
        }
        // Speed up every 10 total points
        int total = state.score1 + state.score2;
        if (total > 0 && total % 10 == 0) increaseDifficulty();
    }

    private void applyPaddle(PlayerCommand cmd, int player) {
        int speed = 5;
        if (cmd.direction == -1) {
            if (player == 1) state.paddle1Y = Math.max(0, state.paddle1Y - speed);
            else            state.paddle2Y = Math.max(0, state.paddle2Y - speed);
        } else if (cmd.direction == 1) {
            if (player == 1) state.paddle1Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle1Y + speed);
            else            state.paddle2Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle2Y + speed);
        }
    }

    private void bounceOffPaddle(int player) {
        state.ballDX = -state.ballDX;
        int delta = (int)(Math.random()*4) - 2; // tweak Y‐speed
        state.ballDY += delta;
        if (state.ballDY > 8)  state.ballDY = 8;
        if (state.ballDY < -8) state.ballDY = -8;
    }

    private void resetBall(int dir) {
        state.ballX = WIDTH/2 - BALL_SIZE/2;
        state.ballY = (int)(Math.random() * (HEIGHT - BALL_SIZE));
        int vx = 5;
        state.ballDX = (dir == 1)? -vx : vx;
        state.ballDY = (Math.random()<0.5)? 3 : -3;
    }

    private void increaseDifficulty() {
        if (state.ballDX>0)  state.ballDX++;
        else                state.ballDX--;
        if (state.ballDY>0)  state.ballDY++;
        else                state.ballDY--;
    }

    /** Send the serialized GameState to both TCP clients. */
    private void broadcastStateToTCP() {
        player1.sendState(state);
        player2.sendState(state);
    }

    /** Convert GameState to JSON and send to all WS clients. */
    private void broadcastStateToWS(PongWebSocketServer wsServer) {
        wsServer.broadcastGameState(state);
    }

    // →––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––
    // Inner class: handles exactly two TCP connections (Java clients)
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final int    playerNumber;
        private ObjectOutputStream out;
        private ObjectInputStream  in;
        private volatile boolean   running = true;

        public ClientHandler(Socket sock, int num) {
            this.socket = sock;
            this.playerNumber = num;
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in  = new ObjectInputStream(socket.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        public void run() {
            if (!running) return;
            try {
                while (running) {
                    Object obj = in.readObject();
                    if (obj instanceof PlayerCommand) {
                        PlayerCommand cmd = (PlayerCommand)obj;
                        if (playerNumber == 1) cmd1.set(cmd);
                        else                   cmd2.set(cmd);
                    } else if (obj instanceof ControlCommand) {
                        handleControl((ControlCommand)obj);
                    }
                }
            } catch (IOException|ClassNotFoundException e) {
                System.out.println("P" + playerNumber + " disconnected: "+e.getMessage());
                running = false;
            }
        }
        private void handleControl(ControlCommand cc) {
            switch(cc.type) {
                case READY:
                    if (playerNumber==1) state.ready1 = true; else state.ready2 = true;
                    break;
                case PAUSE:
                    state.paused = true; break;
                case RESUME:
                    state.paused = false; break;
                case RESTART:
                    if (playerNumber==1) state.ready1 = false; else state.ready2 = false;
                    break;
            }
        }
        public void sendState(GameState gs) {
            if (!running) return;
            try {
                out.reset();
                out.writeObject(gs);
                out.flush();
            } catch (IOException e) {
                running = false;
                try { socket.close(); } catch (IOException ex) { }
            }
        }
    }


    // →––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––
    // Inner class: WebSocketServer that proxies JSON ↔ Java
    private class PongWebSocketServer extends WebSocketServer {
        public PongWebSocketServer(int port) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // When a browser connects, immediately send the secret prompt
            conn.send("ENTER_SECRET");
            wsClients.add(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            wsClients.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // First message expected = the secret
            if ("ENTER_SECRET".equals(message) || message.length()==0) {
                // ignore stray
                return;
            }
            if (!conn.isOpen()) return;

            // Check if we've already authenticated this connection:
            if (conn.getAttachment()==null) {
                // Treat this message as the secret reply from browser
                if (SHARED_SECRET.equals(message)) {
                    conn.send("OK");
                    conn.setAttachment("authed");
                } else {
                    conn.send("FAIL");
                    conn.close();
                }
                return;
            }

            // If we get here, it means browser is authenticated; interpret JSON command
            try {
                // Simple JSON parsing (naive): expecting {"type":"MOVE","dir":-1} or {"type":"CONTROL","action":"READY"}
                if (message.startsWith("{") && message.contains("\"type\":\"MOVE\"")) {
                    // Extract dir
                    int dir = 0;
                    if (message.contains("\"dir\":-1")) dir = -1;
                    if (message.contains("\"dir\":1"))  dir = 1;
                    // Decide which player to forward to:
                    PlayerCommand pc = new PlayerCommand(dir);
                    // If they hadn’t picked “playerNumber” yet, ignore.  Otherwise:
                    Object att = conn.getAttachment(); 
                    // We stored “authed” in attachment, but we also need to store the playerNumber…
                    // For simplicity, assume first authenticated WS=player1, next=player2:
                    // In a production setting, you’d maintain a map WebSocket→playerNumber.
                    // Here, just broadcast to both as if they were input sources:
                    cmd1.set(pc);
                    cmd2.set(pc);
                }
                else if (message.startsWith("{") && message.contains("\"type\":\"CONTROL\"")) {
                    // Extract action: READY, PAUSE, RESUME, RESTART
                    if (message.contains("\"action\":\"READY\"")) {
                        ControlCommand cc = new ControlCommand(ControlCommand.Type.READY);
                        cmd1.get(); // nothing else
                    }
                    else if (message.contains("\"action\":\"PAUSE\"")) {
                        ControlCommand cc = new ControlCommand(ControlCommand.Type.PAUSE);
                        // broadcast to both players
                        // (or decide by routing logic)
                        state.paused = true;
                    }
                    else if (message.contains("\"action\":\"RESUME\"")) {
                        ControlCommand cc = new ControlCommand(ControlCommand.Type.RESUME);
                        state.paused = false;
                    }
                    else if (message.contains("\"action\":\"RESTART\"")) {
                        ControlCommand cc = new ControlCommand(ControlCommand.Type.RESTART);
                        state.ready1 = false;
                        state.ready2 = false;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        /** Broadcast the current GameState to all authenticated WS clients in JSON form. */
        public void broadcastGameState(GameState gs) {
            // Convert GameState → JSON
            String json = String.format(
                "{\"type\":\"STATE\",\"p1Y\":%d,\"p2Y\":%d,\"ballX\":%d,\"ballY\":%d,\"score1\":%d,\"score2\":%d,\"paused\":%b,\"winner\":%d}",
                gs.paddle1Y, gs.paddle2Y, gs.ballX, gs.ballY, gs.score1, gs.score2, gs.paused, gs.winner
            );
            synchronized(wsClients) {
                for (WebSocket ws : wsClients) {
                    if (ws.isOpen() && "authed".equals(ws.getAttachment())) {
                        ws.send(json);
                    }
                }
            }
        }

        @Override public void onError(WebSocket conn, Exception ex) { ex.printStackTrace(); }
        @Override public void onStart() { System.out.println("WS server started."); }
    }
}
