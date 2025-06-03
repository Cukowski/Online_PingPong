import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * PongServer.java
 *
 * Allows either two TCP clients (Java) or two WebSocket clients (browser) to play Pong.
 * Each slot (player 1 or player 2) can be taken by a TCP client or a WebSocket client.
 * Waits until both slots are occupied, then waits for both to click READY.
 */
public class PongServer {
    public static final int TCP_PORT = 12345;
    public static final int WS_PORT  = 8080;
    private static final String SHARED_SECRET;

    static {
        String s = System.getenv("PONG_SECRET");
        if (s == null || s.isEmpty()) {
            System.err.println("Error: PONG_SECRET environment variable not set.");
            System.exit(1);
        }
        SHARED_SECRET = s;
    }

    // Game dimensions
    private static final int WIDTH         = 800;
    private static final int HEIGHT        = 600;
    private static final int PADDLE_WIDTH  = 10;
    private static final int PADDLE_HEIGHT = 80;
    private static final int BALL_SIZE     = 15;
    private static final int TARGET_SCORE  = 5;

    // Shared game state
    private final GameState state = new GameState();
    private final AtomicReference<PlayerCommand> cmd1 = new AtomicReference<>(new PlayerCommand(0));
    private final AtomicReference<PlayerCommand> cmd2 = new AtomicReference<>(new PlayerCommand(0));

    // TCP client handlers (null if not used)
    private ClientHandler player1TCP, player2TCP;

    // WebSocket slot flags
    private volatile boolean player1WS = false;
    private volatile boolean player2WS = false;

    // Set of active WebSocket connections
    private final Set<WebSocket> wsClients = Collections.synchronizedSet(new HashSet<>());

    public static void main(String[] args) {
        new PongServer().start();
    }

    private void start() {
        try {
            // 1) Launch WebSocket server on port 8080
            PongWebSocketServer wsServer = new PongWebSocketServer(WS_PORT);
            wsServer.start();
            System.out.println(">> WebSocketServer listening on port " + WS_PORT);

            // 2) Launch TCP server on port 12345
            try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
                System.out.println(">> TCP Server listening on port " + TCP_PORT);
                // Set a short timeout so we can periodically check WS flags
                serverSocket.setSoTimeout(1000);

                // Wait until slot1 is filled by TCP or WS
                while (!player1WS && player1TCP == null) {
                    try {
                        if (player1TCP == null) {
                            Socket sock = serverSocket.accept();
                            // Attempt to authenticate as player 1
                            ClientHandler handler = authenticateTCP(sock, 1);
                            if (handler != null) {
                                player1TCP = handler;
                                System.out.println("Player 1 will use TCP socket.");
                            }
                        }
                    } catch (SocketTimeoutException ste) {
                        // Timeout: loop again to check player1WS
                    }
                }

                // Wait until slot2 is filled by TCP or WS
                while (!player2WS && player2TCP == null) {
                    try {
                        if (player2TCP == null) {
                            Socket sock = serverSocket.accept();
                            ClientHandler handler = authenticateTCP(sock, 2);
                            if (handler != null) {
                                player2TCP = handler;
                                System.out.println("Player 2 will use TCP socket.");
                            }
                        }
                    } catch (SocketTimeoutException ste) {
                        // Timeout: loop again to check player2WS
                    }
                }

                // Start TCP handler threads for any TCP players
                if (player1TCP != null) new Thread(player1TCP).start();
                if (player2TCP != null) new Thread(player2TCP).start();

                // Both slots occupied now
                while (true) {
                    initGame();
                    waitForReady();
                    resetBall(2);
                    runMatchLoop(wsServer);
                    System.out.println("Match ended. Winner: Player " + state.winner);
                    waitForRestart();
                    System.out.println("Preparing next match...");
                }
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Attempt to authenticate a TCP socket as playerNumber.  If secret matches, return handler; otherwise close.
     */
    private ClientHandler authenticateTCP(Socket sock, int playerNumber) {
        try {
            BufferedWriter textOut = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
            BufferedReader textIn  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            textOut.write("ENTER_SECRET\n");
            textOut.flush();

            sock.setSoTimeout(5000);
            String received = textIn.readLine();
            if (!SHARED_SECRET.equals(received)) {
                System.out.println("TCP Player " + playerNumber + " failed auth: " + received);
                sock.close();
                return null;
            }
            sock.setSoTimeout(0);
            System.out.println("TCP Player " + playerNumber + " authenticated.");
            return new ClientHandler(sock, playerNumber);
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ex) {}
            return null;
        }
    }

    private void initGame() {
        state.paddle1Y = HEIGHT/2 - PADDLE_HEIGHT/2;
        state.paddle2Y = HEIGHT/2 - PADDLE_HEIGHT/2;
        state.score1   = 0;
        state.score2   = 0;
        state.ready1   = false;
        state.ready2   = false;
        state.paused   = false;
        state.winner   = 0;
        cmd1.set(new PlayerCommand(0));
        cmd2.set(new PlayerCommand(0));
    }

    private void waitForReady() throws InterruptedException {
        while (!state.ready1 || !state.ready2) {
            broadcastStateToAll();
            Thread.sleep(100);
        }
    }

    private void waitForRestart() throws InterruptedException {
        while (state.ready1 || state.ready2) {
            Thread.sleep(100);
        }
    }

    private void runMatchLoop(PongWebSocketServer wsServer) throws InterruptedException {
        final int FPS       = 60;
        final long frameTime = 1000 / FPS;
        while (state.winner == 0) {
            long start = System.currentTimeMillis();
            if (!state.paused) updateGame();
            broadcastStateToAll();
            Thread.sleep(Math.max(0, frameTime - (System.currentTimeMillis() - start)));
        }
        broadcastStateToAll();
    }

    private void updateGame() {
        applyPaddle(cmd1.get(), 1);
        applyPaddle(cmd2.get(), 2);
        state.ballX += state.ballDX;
        state.ballY += state.ballDY;
        if (state.ballY <= 0 || state.ballY + BALL_SIZE >= HEIGHT) {
            state.ballDY = -state.ballDY;
        }
        if (state.ballX <= PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle1Y && state.ballY <= state.paddle1Y + PADDLE_HEIGHT) {
                bounceOffPaddle(1);
            } else {
                state.score2++;
                if (state.score2 >= TARGET_SCORE) state.winner = 2;
                else resetBall(1);
            }
        }
        if (state.ballX + BALL_SIZE >= WIDTH - PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle2Y && state.ballY <= state.paddle2Y + PADDLE_HEIGHT) {
                bounceOffPaddle(2);
            } else {
                state.score1++;
                if (state.score1 >= TARGET_SCORE) state.winner = 1;
                else resetBall(2);
            }
        }
        int total = state.score1 + state.score2;
        if (total > 0 && total % 10 == 0) increaseDifficulty();
    }

    private void applyPaddle(PlayerCommand cmd, int player) {
        int speed = 5;
        if (cmd.direction == -1) {
            if (player == 1) state.paddle1Y = Math.max(0, state.paddle1Y - speed);
            else             state.paddle2Y = Math.max(0, state.paddle2Y - speed);
        } else if (cmd.direction == 1) {
            if (player == 1) state.paddle1Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle1Y + speed);
            else             state.paddle2Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle2Y + speed);
        }
    }

    private void bounceOffPaddle(int player) {
        state.ballDX = -state.ballDX;
        int delta = (int)(Math.random() * 4) - 2;
        state.ballDY += delta;
        state.ballDY = Math.max(-8, Math.min(8, state.ballDY));
    }

    private void resetBall(int dir) {
        state.ballX = WIDTH/2 - BALL_SIZE/2;
        state.ballY = (int)(Math.random() * (HEIGHT - BALL_SIZE));
        int vx = 5;
        state.ballDX = (dir == 1) ? -vx : vx;
        state.ballDY = (Math.random() < 0.5) ? 3 : -3;
    }

    private void increaseDifficulty() {
        state.ballDX += (state.ballDX > 0 ? 1 : -1);
        state.ballDY += (state.ballDY > 0 ? 1 : -1);
    }

    // Broadcast state to both TCP and WebSocket clients
    private void broadcastStateToAll() {
        if (player1TCP != null) player1TCP.sendState(state);
        if (player2TCP != null) player2TCP.sendState(state);
        // WebSocket broadcast includes only those who chose a slot (attachment is Integer)
        String json = String.format(
          "{"
        +   "\"type\":\"STATE\","                    // message type
        +   "\"p1Y\":%d,\"p2Y\":%d,"                  // paddle positions
        +   "\"ballX\":%d,\"ballY\":%d,"              // ball
        +   "\"score1\":%d,\"score2\":%d,"              // scores
        +   "\"paused\":%b,\"winner\":%d,"             // paused + winner
        +   "\"ready1\":%b,\"ready2\":%b"               // ready flags
        +   "}",
          state.paddle1Y,
          state.paddle2Y,
          state.ballX,
          state.ballY,
          state.score1,
          state.score2,
          state.paused,
          state.winner,
          state.ready1,
          state.ready2
        );
        synchronized (wsClients) {
            for (WebSocket w : wsClients) {
                Object attach = w.getAttachment();
                if (w.isOpen() && attach instanceof Integer) {
                    w.send(json);
                }
            }
        }
    }

    // ─── TCP ClientHandler ─────────────────────────────────────────────────────────
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
            try {
                while (running) {
                    Object obj = in.readObject();
                    if (obj instanceof PlayerCommand) {
                        PlayerCommand cmd = (PlayerCommand) obj;
                        if (playerNumber == 1) cmd1.set(cmd);
                        else                   cmd2.set(cmd);
                    }
                    else if (obj instanceof ControlCommand) {
                        ControlCommand cc = (ControlCommand) obj;
                        handleControlTCP(cc, playerNumber);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Player " + playerNumber + " disconnected: " + e.getMessage());
                running = false;
            }
        }

        private void handleControlTCP(ControlCommand cc, int playerNum) {
            switch (cc.type) {
                case READY:
                    if (playerNum == 1) {
                        state.ready1 = true;
                        System.out.println("Player 1 is ready.");
                    } else {
                        state.ready2 = true;
                        System.out.println("Player 2 is ready.");
                    }
                    break;
                case PAUSE:
                    state.paused = true;
                    System.out.println("Game paused by player " + playerNum + ".");
                    break;
                case RESUME:
                    state.paused = false;
                    System.out.println("Game resumed by player " + playerNum + ".");
                    break;
                case RESTART:
                    if (playerNum == 1) state.ready1 = false;
                    else                 state.ready2 = false;
                    System.out.println("Player " + playerNum + " requested restart.");
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

    // ─── WebSocket Server ──────────────────────────────────────────────────────────
    private class PongWebSocketServer extends WebSocketServer {
        public PongWebSocketServer(int port) {
            super(new InetSocketAddress("0.0.0.0", port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("WebSocket: new connection—waiting for secret...");
            conn.send("ENTER_SECRET");
            wsClients.add(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("WebSocket: connection closed: " + reason);
            // If this WS had chosen a slot, clear that slot flag
            Object attach = conn.getAttachment();
            if (attach instanceof Integer) {
                int slot = (Integer) attach;
                if (slot == 1) player1WS = false;
                else           player2WS = false;
            }
            wsClients.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            message = message.trim();

            // 1) Password handshake
            if (conn.getAttachment() == null) {
                if (SHARED_SECRET.equals(message)) {
                    conn.send("OK");
                    conn.setAttachment("authed");
                    System.out.println("WebSocket client authenticated.");
                } else {
                    conn.send("FAIL");
                    System.out.println("WebSocket client failed auth: " + message);
                    conn.close();
                }
                return;
            }

            // 2) Now expecting { "action":"CHOOSE_PLAYER","p":1 } or p:2
            Object attach = conn.getAttachment();
            if ("authed".equals(attach)) {
                JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
                if (obj.has("action") && "CHOOSE_PLAYER".equals(obj.get("action").getAsString())) {
                    int p = obj.get("p").getAsInt(); // must be 1 or 2
                    if (p == 1) {
                        player1WS = true;
                        conn.setAttachment(1);
                        System.out.println("WebSocket assigned to Player 1");
                    } else if (p == 2) {
                        player2WS = true;
                        conn.setAttachment(2);
                        System.out.println("WebSocket assigned to Player 2");
                    } else {
                        System.out.println("WebSocket bad player number: " + p);
                        conn.close();
                    }
                }
                return;
            }

            // 3) Handle MOVE or CONTROL for a known player
            int wsP = (Integer) conn.getAttachment();
            JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
            String t = obj.get("type").getAsString();

            if ("MOVE".equals(t)) {
                int dir = obj.get("dir").getAsInt();
                if (wsP == 1) cmd1.set(new PlayerCommand(dir));
                else         cmd2.set(new PlayerCommand(dir));
            }
            else if ("CONTROL".equals(t)) {
                String action = obj.get("action").getAsString();
                ControlCommand cc = new ControlCommand(ControlCommand.Type.valueOf(action));
                if (wsP == 1) {
                    switch (cc.type) {
                        case READY:
                            state.ready1 = true;
                            System.out.println("Player 1 is ready.");
                            break;
                        case PAUSE:
                            state.paused = true;
                            System.out.println("Game paused by Player 1.");
                            break;
                        case RESUME:
                            state.paused = false;
                            System.out.println("Game resumed by Player 1.");
                            break;
                        case RESTART:
                            state.ready1 = false;
                            System.out.println("Player 1 requested restart.");
                            break;
                    }
                } else {
                    switch (cc.type) {
                        case READY:
                            state.ready2 = true;
                            System.out.println("Player 2 is ready.");
                            break;
                        case PAUSE:
                            state.paused = true;
                            System.out.println("Game paused by Player 2.");
                            break;
                        case RESUME:
                            state.paused = false;
                            System.out.println("Game resumed by Player 2.");
                            break;
                        case RESTART:
                            state.ready2 = false;
                            System.out.println("Player 2 requested restart.");
                            break;
                    }
                }
            }
        }

        /**
         * Broadcast the full GameState (including ready1/ready2) as JSON to every WebSocket client
         * that has already chosen a slot (attachment is Integer).
         */
        public void broadcastGameState(GameState gs) {
            String json = String.format(
              "{"
            +   "\"type\":\"STATE\","                    // message type
            +   "\"p1Y\":%d,\"p2Y\":%d,"                  // paddle positions
            +   "\"ballX\":%d,\"ballY\":%d,"              // ball
            +   "\"score1\":%d,\"score2\":%d,"              // scores
            +   "\"paused\":%b,\"winner\":%d,"             // paused + winner
            +   "\"ready1\":%b,\"ready2\":%b"               // ready flags
            +   "}",
              gs.paddle1Y,
              gs.paddle2Y,
              gs.ballX,
              gs.ballY,
              gs.score1,
              gs.score2,
              gs.paused,
              gs.winner,
              gs.ready1,
              gs.ready2
            );
            synchronized (wsClients) {
                for (WebSocket w : wsClients) {
                    Object attach = w.getAttachment();
                    if (w.isOpen() && attach instanceof Integer) {
                        w.send(json);
                    }
                }
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
        }

        @Override
        public void onStart() {
            System.out.println("WebSocket server started.");
        }
    }
}
