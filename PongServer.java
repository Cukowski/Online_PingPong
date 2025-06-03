import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main server that accepts exactly two clients and runs the game loop.
 * Waits for both players to click “READY,” allows pausing/resuming, and supports restart.
 * Never exits on its own; use Ctrl+C to terminate.
 */
public class PongServer {
    public static final int PORT = 12345;
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
        System.out.println("Server starting on port " + PORT + "...");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            // Accept exactly two clients
            Socket socket1 = serverSocket.accept();
            System.out.println("Player 1 connected.");
            player1 = new ClientHandler(socket1, 1);

            Socket socket2 = serverSocket.accept();
            System.out.println("Player 2 connected.");
            player2 = new ClientHandler(socket2, 2);

            // Start client handler threads
            new Thread(player1).start();
            new Thread(player2).start();

            // Main loop: keep restarting matches indefinitely
            while (true) {
                initGame();

                // Broadcast initial “waiting” state until both players press READY
                waitForReady();

                // Once ready, launch ball toward player 2 by default
                resetBall(2);

                // Run match until someone wins
                runMatchLoop();

                // At this point state.winner != 0 ; final state has been broadcast.
                System.out.println("Match over. Winner: Player " + state.winner);

                // Now wait for both clients to click RESTART (which clears their ready flags)
                waitForRestart();

                // Loop will re-init and start a fresh match
                System.out.println("Restarting match...");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Initialize positions, scores, and control flags for a new match.
     */
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

    /**
     * Broadcast "waiting for READY" until both ready1 and ready2 become true.
     */
    private void waitForReady() throws InterruptedException {
        while (!state.ready1 || !state.ready2) {
            broadcastState();
            Thread.sleep(100);
        }
    }

    /**
     * After a match ends (state.winner != 0), each client’s RESTART clears
     * that player's ready flag. We wait until both ready1 and ready2 are false
     * again before starting the next match.
     */
    private void waitForRestart() throws InterruptedException {
        while (state.ready1 || state.ready2) {
            Thread.sleep(100);
        }
    }

    /**
     * Run the core physics loop until someone wins (state.winner != 0).
     * Broadcasts at ~60 FPS, respecting state.paused.
     */
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
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }

        // Send final state one more time so clients can display end‐of‐game screen
        broadcastState();
    }

    /**
     * Update positions, detect collisions, award points, and set state.winner when reached.
     */
    private void updateGame() {
        // Apply paddle movement from last commands
        applyPaddleCommand(cmd1.get(), 1);
        applyPaddleCommand(cmd2.get(), 2);

        // Move ball
        state.ballX += state.ballDX;
        state.ballY += state.ballDY;

        // Bounce off top or bottom
        if (state.ballY <= 0 || state.ballY + BALL_SIZE >= HEIGHT) {
            state.ballDY = -state.ballDY;
        }

        // Left paddle collision / miss
        if (state.ballX <= PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle1Y && state.ballY <= state.paddle1Y + PADDLE_HEIGHT) {
                bounceOffPaddle(1);
            } else {
                state.score2++;
                if (state.score2 >= TARGET_SCORE) {
                    state.winner = 2;
                } else {
                    resetBall(1);
                }
            }
        }

        // Right paddle collision / miss
        if (state.ballX + BALL_SIZE >= WIDTH - PADDLE_WIDTH) {
            if (state.ballY + BALL_SIZE >= state.paddle2Y && state.ballY <= state.paddle2Y + PADDLE_HEIGHT) {
                bounceOffPaddle(2);
            } else {
                state.score1++;
                if (state.score1 >= TARGET_SCORE) {
                    state.winner = 1;
                } else {
                    resetBall(2);
                }
            }
        }

        // Slowly increase difficulty every 10 total points
        int totalPoints = state.score1 + state.score2;
        if (totalPoints > 0 && totalPoints % 10 == 0) {
            increaseDifficulty();
        }
    }

    private void applyPaddleCommand(PlayerCommand cmd, int player) {
        int speed = 5;
        if (cmd.direction == -1) {
            if (player == 1) {
                state.paddle1Y = Math.max(0, state.paddle1Y - speed);
            } else {
                state.paddle2Y = Math.max(0, state.paddle2Y - speed);
            }
        } else if (cmd.direction == 1) {
            if (player == 1) {
                state.paddle1Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle1Y + speed);
            } else {
                state.paddle2Y = Math.min(HEIGHT - PADDLE_HEIGHT, state.paddle2Y + speed);
            }
        }
    }

    private void bounceOffPaddle(int player) {
        state.ballDX = -state.ballDX;
        int delta = (int) (Math.random() * 4) - 2; // random tweak -2..1
        state.ballDY += delta;
        if (state.ballDY > 8) state.ballDY = 8;
        if (state.ballDY < -8) state.ballDY = -8;
    }

    private void resetBall(int directionTo) {
        state.ballX = WIDTH / 2 - BALL_SIZE / 2;
        state.ballY = (int) (Math.random() * (HEIGHT - BALL_SIZE));
        int speedX = 5;
        state.ballDX = (directionTo == 1) ? -speedX : speedX;
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

    /**
     * Handles a single client's connection: reading PlayerCommand or ControlCommand and updating state.
     */
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
            try {
                while (running) {
                    Object obj = in.readObject();
                    if (obj instanceof PlayerCommand) {
                        PlayerCommand cmd = (PlayerCommand) obj;
                        if (playerNumber == 1) {
                            cmd1.set(cmd);
                        } else {
                            cmd2.set(cmd);
                        }
                    } else if (obj instanceof ControlCommand) {
                        ControlCommand cc = (ControlCommand) obj;
                        handleControl(cc);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Player " + playerNumber + " disconnected.");
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
                    // Clearing this player's ready flag; main loop will see both false to restart
                    if (playerNumber == 1) state.ready1 = false;
                    else state.ready2 = false;
                    System.out.println("Player " + playerNumber + " requested restart.");
                    break;
            }
        }

        public void sendState(GameState gs) {
            try {
                out.reset();
                out.writeObject(gs);
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
