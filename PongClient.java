import java.io.*;
import java.net.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Client that connects to PongServer, performs a passkey handshake,
 * prompts for player number, and renders the game state with
 * READY/PAUSE/RESTART controls.
 */
public class PongClient extends JPanel implements KeyListener {
    // Logical game dimensions (match server's 800x600)
    private static final int GAME_WIDTH = 800, GAME_HEIGHT = 600;
    private static final int PADDLE_HEIGHT = 80, PADDLE_WIDTH = 10;
    private static final int BALL_SIZE = 15;

    // Network components
    private Socket socket;
    private BufferedReader textIn;
    private BufferedWriter textOut;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    // Current game state from server
    private volatile GameState state = new GameState();

    // Last movement command to send
    private PlayerCommand lastCmd = new PlayerCommand(0);

    // Player number (1 or 2)
    private int playerNumber;

    // Message to display on game over screen
    private String gameOverMessage = null;

    public static void main(String[] args) {
        String host = JOptionPane.showInputDialog("Enter server hostname:", "");
        String portStr = JOptionPane.showInputDialog("Enter server port:", "12345");
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Invalid port. Using 12345.");
            port = 12345;
        }

        // Establish connection and perform secret handshake
        PongClient client = new PongClient(host, port);

        // Prompt for player number (loop until valid)
        int pNum;
        while (true) {
            String pNumStr = JOptionPane.showInputDialog("Enter player number (1 or 2):", "1");
            try {
                pNum = Integer.parseInt(pNumStr);
                if (pNum == 1 || pNum == 2) break;
            } catch (NumberFormatException ignored) { }
            JOptionPane.showMessageDialog(null, "Invalid player number. Please enter 1 or 2.");
        }
        client.setPlayerNumber(pNum);

        // Build GUI
        JFrame frame = new JFrame("Networked Pong - Player " + pNum);
        frame.setSize(1600, 960);
        frame.setLayout(new BorderLayout());
        frame.add(client, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        JButton readyButton = new JButton("READY");
        JButton pauseButton = new JButton("PAUSE");
        JButton restartButton = new JButton("RESTART");
        buttonPanel.add(readyButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(restartButton);
        frame.add(buttonPanel, BorderLayout.SOUTH);

        readyButton.addActionListener(e -> {
            client.sendControl(new ControlCommand(ControlCommand.Type.READY));
            readyButton.setEnabled(false);
            client.requestFocusInWindow();
            client.gameOverMessage = null;
        });
        pauseButton.addActionListener(e -> {
            if (!client.getState().paused && client.getState().winner == 0) {
                client.sendControl(new ControlCommand(ControlCommand.Type.PAUSE));
            } else if (client.getState().winner == 0) {
                client.sendControl(new ControlCommand(ControlCommand.Type.RESUME));
            }
            client.requestFocusInWindow();
        });
        restartButton.addActionListener(e -> {
            client.sendControl(new ControlCommand(ControlCommand.Type.RESTART));
            readyButton.setEnabled(true);
            client.requestFocusInWindow();
            client.gameOverMessage = null;
        });

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Ensure panel has focus for key events
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                client.requestFocusInWindow();
            }
        });

        client.start();
    }

    public PongClient(String host, int port) {
        try {
            socket = new Socket(host, port);
            textIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            textOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            // Wait for server prompt
            String prompt = textIn.readLine();
            if ("ENTER_SECRET".equals(prompt)) {
                String secret = JOptionPane.showInputDialog("Enter shared secret:", "");
                textOut.write(secret + "\n");
                textOut.flush();
            } else {
                throw new IOException("Unexpected prompt from server: " + prompt);
            }

            // Wrap object streams after handshake
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Unable to connect or authenticate: " + e.getMessage());
            System.exit(0);
        }
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);

        // Thread to listen for GameState updates
        new Thread(() -> {
            try {
                while (true) {
                    Object obj = in.readObject();
                    if (obj instanceof GameState) {
                        state = (GameState) obj;
                        if (state.winner != 0 && gameOverMessage == null) {
                            gameOverMessage = (state.winner == playerNumber) ? "You Win" : "You Lose";
                        }
                        repaint();
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                JOptionPane.showMessageDialog(this, "Connection lost: " + e.getMessage());
                System.exit(0);
            }
        }).start();

        // Repaint timer at ~60 FPS
        new Timer(1000 / 60, e -> repaint()).start();
    }

    private void start() {
        // No additional initialization needed
    }

    public void setPlayerNumber(int pNum) {
        this.playerNumber = pNum;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int width = getWidth();
        int height = getHeight();

        // Waiting screen
        if (!state.ready1 || !state.ready2) {
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 36));
            String waitingText = "Waiting for both players to be READY...";
            int textWidth = g2.getFontMetrics().stringWidth(waitingText);
            g2.drawString(waitingText, (width - textWidth) / 2, height / 2);
            return;
        }

        // Game over screen
        if (state.winner != 0) {
            drawFinalFrame(g2, width, height);
            return;
        }

        // Draw center dashed line
        g2.setColor(Color.GRAY);
        Stroke dashed = new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{10}, 0);
        g2.setStroke(dashed);
        g2.drawLine(width / 2, 0, width / 2, height);

        float xScale = width / (float) GAME_WIDTH;
        float yScale = height / (float) GAME_HEIGHT;

        // Draw paddles
        int paddle1Y = Math.round(state.paddle1Y * yScale);
        int paddle2Y = Math.round(state.paddle2Y * yScale);
        int paddleW = Math.round(PADDLE_WIDTH * xScale);
        int paddleH = Math.round(PADDLE_HEIGHT * yScale);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, paddle1Y, paddleW, paddleH);
        g2.fillRect(width - paddleW, paddle2Y, paddleW, paddleH);

        // Draw ball
        int ballX = Math.round(state.ballX * xScale);
        int ballY = Math.round(state.ballY * yScale);
        int ballSize = Math.round(BALL_SIZE * xScale);
        if (!state.paused) {
            g2.fillOval(ballX, ballY, ballSize, ballSize);
        }

        // Paused overlay
        if (state.paused) {
            g2.setColor(new Color(255, 255, 255, 128));
            g2.fillRect(0, 0, width, height);
            g2.setColor(Color.RED);
            g2.setFont(new Font("Consolas", Font.BOLD, 72));
            String pauseText = "PAUSED";
            int ptWidth = g2.getFontMetrics().stringWidth(pauseText);
            g2.drawString(pauseText, (width - ptWidth) / 2, height / 2);
            return;
        }

        g2.setColor(Color.WHITE);
        g2.fillOval(ballX, ballY, ballSize, ballSize);

        // Draw scores
        g2.setFont(new Font("Consolas", Font.BOLD, 36));
        String s1 = String.valueOf(state.score1);
        String s2 = String.valueOf(state.score2);
        g2.drawString(s1, width / 2 - 50, 50);
        g2.drawString(s2, width / 2 + 25, 50);
    }

    private void drawFinalFrame(Graphics2D g2, int width, int height) {
        g2.setColor(new Color(0, 0, 0, 100));
        g2.fillRect(0, 0, width, height);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Consolas", Font.BOLD, 72));
        if (gameOverMessage != null) {
            int msgWidth = g2.getFontMetrics().stringWidth(gameOverMessage);
            g2.drawString(gameOverMessage, (width - msgWidth) / 2, height / 2);
        }

        g2.setFont(new Font("Consolas", Font.BOLD, 48));
        String s1 = "P1: " + state.score1;
        String s2 = "P2: " + state.score2;
        int s1Width = g2.getFontMetrics().stringWidth(s1);
        int s2Width = g2.getFontMetrics().stringWidth(s2);
        g2.drawString(s1, (width / 2) - s1Width - 20, height / 2 + 60);
        g2.drawString(s2, (width / 2) + 20, height / 2 + 60);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (!state.ready1 || !state.ready2 || state.paused || state.winner != 0) return;
        int dir = 0;
        if (e.getKeyCode() == KeyEvent.VK_W || e.getKeyCode() == KeyEvent.VK_UP) dir = -1;
        if (e.getKeyCode() == KeyEvent.VK_S || e.getKeyCode() == KeyEvent.VK_DOWN) dir = 1;
        lastCmd = new PlayerCommand(dir);
        sendMovement(lastCmd);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (!state.ready1 || !state.ready2 || state.paused || state.winner != 0) return;
        boolean relevantKey = (e.getKeyCode() == KeyEvent.VK_W || e.getKeyCode() == KeyEvent.VK_S ||
                              e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN);
        if (relevantKey) {
            lastCmd = new PlayerCommand(0);
            sendMovement(lastCmd);
        }
    }

    @Override
    public void keyTyped(KeyEvent e) { }

    public void sendMovement(PlayerCommand cmd) {
        try {
            out.reset();
            out.writeObject(cmd);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendControl(ControlCommand cc) {
        try {
            out.reset();
            out.writeObject(cc);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public GameState getState() {
        return state;
    }
}
