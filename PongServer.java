import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main server that accepts exactly two clients and runs the game loop.
 * Sends a passkey prompt "ENTER_SECRET", reads client response within 5 seconds.
 * If secret matches environment, proceeds; otherwise rejects and keeps running.
 * Never exits on its own; use Ctrl+C to terminate.
 */
public class PongServer {
    public static final int PORT = 12345;
    private static final String SHARED_SECRET;
    private static final int TIMEOUT_MS = 5000; // 5-second timeout for secret

    static {
        String secret = System.getenv("PONG_SECRET");
        if (secret == null || secret.isEmpty()) {
            System.err.println("Error: PONG_SECRET environment variable not set.");
            System.exit(1);
        }
        SHARED_SECRET = secret;
    }

    private static final int WIDTH = 800, HEIGHT = 600;
    private static final int PADDLE_HEIGHT = 80;
    private static final int PADDLE_WIDTH = 10;
    private static final int BALL_SIZE = 15;
    private static final int TARGET_SCORE = 5;

    // Shared game state
    private final GameState state = new GameState();
    // Last movement commands from each player
    private final AtomicReference<PlayerCommand> cmd1 = new AtomicReference<>(new PlayerCommand(0));
    private final AtomicReference<PlayerCommand> cmd2 = new AtomicReference<>(new PlayerCommand(0));

    private ClientHandler player1, player2;

    public static void main(String[] args) {
        new PongServer().start();
    }

    private void start() {
        System.out.println("Server starting on port " + PORT + "... (secret hidden)");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            // Continuously accept for player1 until authenticated
            while (player1 == null) {
                player1 = tryAcceptAndAuthenticate(serverSocket, 1);
            }
            // Continuously accept for player2 until authenticated
            while (player2 == null) {
                player2 = tryAcceptAndAuthenticate(serverSocket, 2);
            }

            // Start client handler threads
            new Thread(player1).start();
            new Thread(player2).start();

            // Main cycle: keep running matches until manual termination
            while (true) {
                initGame();
                waitForReady();
                resetBall(2);
                runMatchLoop();
                System.out.println("Match over. Winner: Player " + state.winner);
                waitForRestart();
                System.out.println("Preparing next match...");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Attempts to accept and authenticate a client; returns handler if successful, or null to retry.
     */
    private ClientHandler tryAcceptAndAuthenticate(ServerSocket serverSocket, int playerNumber) {
        try {
            Socket sock = serverSocket.accept();
            System.out.println("Incoming connection for player " + playerNumber + "...");
            // Send prompt
            BufferedWriter textOut = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()));
            BufferedReader textIn = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            textOut.write("ENTER_SECRET\n");
            textOut.flush();
            try {
                sock.setSoTimeout(TIMEOUT_MS);
                String received = textIn.readLine();
                if (!SHARED_SECRET.equals(received)) {
                    System.out.println("Player " + playerNumber + " failed authentication: " + received);
                    sock.close();
                    return null;
                }
            } catch (IOException e) {
                System.out.println("Player " + playerNumber + " authentication timeout or error: " + e.getMessage());
                sock.close();
                return null;
            }
            // Authentication succeeded
            System.out.println("Player " + playerNumber + " authenticated.");
            sock.setSoTimeout(0);
            return new ClientHandler(sock, playerNumber);
        } catch (IOException e) {
            System.out.println("Error accepting player " + playerNumber + ": " + e.getMessage());
            return null;
        }
    }

    private void initGame() {
        state.paddle1Y = HEIGHT / 2 - PADDLE_HEIGHT / 2;
        state.paddle2Y = HEIGHT / 2 - PADDLE_HEIGHT / 2;
        state.score1 = 0;
        state.score2 = 0;
        state.ready1 = false;
        state.ready2 = false;
        state.paused = false;
        state.winner = 0;
        cmd1.set(new PlayerCommand(0));
        cmd2.set(new PlayerCommand(0));
    }

    private void waitForReady() throws InterruptedException {
        while (!state.ready1 || !state.ready2) {
            broadcastState();
            Thread.sleep(100);
        }
    }

    private void waitForRestart() throws InterruptedException {
        while (state.ready1 || state.ready2) {
            Thread.sleep(100);
        }
    }

    private void runMatchLoop() throws InterruptedException {
        final int FPS = 60;
        final long frameTime = 1000 / FPS;
        while (state.winner == 0) {
            long start = System.currentTimeMillis();
            if (!state.paused) {
                updateGame();
            }
            broadcastState();
            long elapsed = System.currentTimeMillis() - start;
            long sleep = frameTime - elapsed;
            if (sleep > 0) Thread.sleep(sleep);
        }
        broadcastState();
    }

    private void updateGame() {
        applyPaddleCommand(cmd1.get(), 1);
        applyPaddleCommand(cmd2.get(), 2);
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

    private void applyPaddleCommand(PlayerCommand cmd, int player) {
        int speed = 5;
        if (cmd.direction == -1) {
            if (player == 1) state.paddle1Y = Math.max(0, state.paddle1Y - speed);
            else state.paddle2Y = Math.max(0, state.paddle2Y - speed);
        } else if (cmd.direction == 1) {
            if (player == 1) state.paddle1Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle1Y + speed);
            else state.paddle2Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle2Y + speed);
        }
    }

    private void bounceOffPaddle(int player) {
        state.ballDX = -state.ballDX;
        int delta = (int) (Math.random() * 4) - 2;
        state.ballDY += delta;
        if (state.ballDY > 8) state.ballDY = 8;
        if (state.ballDY < -8) state.ballDY = -8;
    }

    private void resetBall(int dir) {
        state.ballX = WIDTH / 2 - BALL_SIZE / 2;
        state.ballY = (int) (Math.random() * (HEIGHT - BALL_SIZE));
        int vx = 5;
        state.ballDX = (dir == 1) ? -vx : vx;
        state.ballDY = (Math.random() < 0.5) ? 3 : -3;
    }

    private void increaseDifficulty() {
        if (state.ballDX > 0) state.ballDX++;
        else state.ballDX--;
        if (state.ballDY > 0) state.ballDY++;
        else state.ballDY--;
    }

    private void broadcastState() {
        player1.sendState(state);
        player2.sendState(state);
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final int playerNumber;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private volatile boolean running = true;

        public ClientHandler(Socket sock, int num) {
            this.socket = sock;
            this.playerNumber = num;
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());
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
                        PlayerCommand cmd = (PlayerCommand) obj;
                        if (playerNumber == 1) cmd1.set(cmd);
                        else cmd2.set(cmd);
                    } else if (obj instanceof ControlCommand) {
                        handleControl((ControlCommand) obj);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Player " + playerNumber + " disconnected or error: " + e.getMessage());
                running = false;
            }
        }

        private void handleControl(ControlCommand cc) {
            switch (cc.type) {
                case READY:
                    if (playerNumber == 1) state.ready1 = true;
                    else state.ready2 = true;
                    System.out.println("Player " + playerNumber + " is ready.");
                    break;
                case PAUSE:
                    state.paused = true;
                    System.out.println("Game paused by player " + playerNumber + ".");
                    break;
                case RESUME:
                    state.paused = false;
                    System.out.println("Game resumed by player " + playerNumber + ".");
                    break;
                case RESTART:
                    if (playerNumber == 1) state.ready1 = false;
                    else state.ready2 = false;
                    System.out.println("Player " + playerNumber + " requested restart.");
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
                System.out.println("Error sending state to player " + playerNumber + ": " + e.getMessage());
                running = false;
                try { socket.close(); } catch (IOException ex) { }
            }
        }
    }
}
