import java.io.*;
import java.net.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * PongClient.java
 *
 * - Connects over TCP to PongServer (port 12345).
 * - Waits for the server to send "ENTER_SECRET\n".
 * - Prompts the user for the shared secret (via a Swing JOptionPane).
 * - If correct, switches to Object streams and begins receiving GameState updates.
 * - Renders a 16:9‐scaled Pong board in a Swing JPanel (800×600 logic scaled to window).
 * - Sends PlayerCommand (−1,0,1) on W/S or Up/Down keys, and ControlCommand for READY / PAUSE / RESTART.
 */
public class PongClient extends JPanel implements KeyListener {
    // The server’s logical game size (must match PongServer)
    private static final int GAME_WIDTH  = 800;
    private static final int GAME_HEIGHT = 600;
    private static final int PADDLE_WIDTH  = 10;
    private static final int PADDLE_HEIGHT = 80;
    private static final int BALL_SIZE     = 15;

    // Socket & streams for the initial text handshake (ENTER_SECRET)
    private Socket socket;
    private BufferedReader textIn;
    private BufferedWriter textOut;

    // Once authenticated, we wrap the same socket streams in object streams:
    private ObjectOutputStream out;
    private ObjectInputStream in;

    // Current GameState (updated whenever server sends a new object)
    private volatile GameState state = new GameState();

    // Last paddle movement command (direction = -1, 0, or 1)
    private PlayerCommand lastCmd = new PlayerCommand(0);

    // This client’s slot: 1 or 2 (used for coloring win/lose and sending MOVE to correct server slot)
    private int playerNumber;

    // When a winner arrives, we store “You Win!” or “You Lose”
    private String gameOverMessage = null;

    // ─── Main entrypoint ──────────────────────────────────────────────────────
    public static void main(String[] args) {
        // Ask for hostname (pre‐fill with pong-online.site)
        String host = JOptionPane.showInputDialog(
            null,
            "Enter server hostname (default: pong-online.site):",
            "pong-online.site"
        );
        if (host == null || host.trim().isEmpty()) {
            host = "pong-online.site";
        }

        // Ask for port (pre‐fill with 12345)
        String portStr = JOptionPane.showInputDialog(
            null,
            "Enter server port:",
            "12345"
        );
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(null, "Invalid port. Using 12345.");
            port = 12345;
        }

        // Create the client and perform the ENTER_SECRET handshake immediately
        PongClient client = new PongClient(host, port);

        // Prompt until the user enters 1 or 2
        int pNum;
        while (true) {
            String pNumStr = JOptionPane.showInputDialog(
                "Enter player number (1 or 2):",
                "1"
            );
            try {
                pNum = Integer.parseInt(pNumStr);
                if (pNum == 1 || pNum == 2) break;
            } catch (NumberFormatException ignored) { }
            JOptionPane.showMessageDialog(null, "Invalid player number. Please enter 1 or 2.");
        }
        client.setPlayerNumber(pNum);

        // Build the GUI (scaled 16:9 window)
        JFrame frame = new JFrame("Networked Pong – Player " + pNum);
        frame.setSize(1600, 960); // 16:9 aspect
        frame.setLayout(new BorderLayout());
        frame.add(client, BorderLayout.CENTER);

        // Button row: READY / PAUSE / RESTART
        JPanel buttonPanel = new JPanel();
        JButton readyButton   = new JButton("READY");
        JButton pauseButton   = new JButton("PAUSE");
        JButton restartButton = new JButton("RESTART");
        buttonPanel.add(readyButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(restartButton);
        frame.add(buttonPanel, BorderLayout.SOUTH);

        // “READY” → send ControlCommand.READY, disable button
        readyButton.addActionListener(e -> {
            client.sendControl(new ControlCommand(ControlCommand.Type.READY));
            readyButton.setEnabled(false);
            client.requestFocusInWindow();
            client.gameOverMessage = null;
        });

        // “PAUSE” toggles between PAUSE and RESUME
        pauseButton.addActionListener(e -> {
            if (!client.getState().paused && client.getState().winner == 0) {
                client.sendControl(new ControlCommand(ControlCommand.Type.PAUSE));
                pauseButton.setText("RESUME");
            } else if (client.getState().winner == 0) {
                client.sendControl(new ControlCommand(ControlCommand.Type.RESUME));
                pauseButton.setText("PAUSE");
            }
            client.requestFocusInWindow();
        });

        // “RESTART” → send ControlCommand.RESTART, re‐enable READY
        restartButton.addActionListener(e -> {
            client.sendControl(new ControlCommand(ControlCommand.Type.RESTART));
            readyButton.setEnabled(true);
            pauseButton.setText("PAUSE");
            client.requestFocusInWindow();
            client.gameOverMessage = null;
        });

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Ensure our panel has keyboard focus on window open
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                client.requestFocusInWindow();
            }
        });

        // Kick off the client’s background threads (reading states + repaints)
        client.start();
    }

    // ─── Constructor: connect → handshake → switch to object streams ────────────
    public PongClient(String host, int port) {
        try {
            // 1) Open a TCP socket to (host, port)
            socket = new Socket(host, port);

            // 2) Wrap in text streams for the “ENTER_SECRET” prompt
            textIn  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            textOut = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

            // 3) Server must send “ENTER_SECRET\n”
            String prompt = textIn.readLine();
            if (!"ENTER_SECRET".equals(prompt)) {
                throw new IOException("Expected ENTER_SECRET but got: " + prompt);
            }
            // 4) Ask user for the shared secret
            String secret = JOptionPane.showInputDialog("Enter shared secret:", "");
            if (secret == null) System.exit(0);
            textOut.write(secret + "\n");
            textOut.flush();

            // 5) Now wrap again in object streams
            out = new ObjectOutputStream(socket.getOutputStream());
            in  = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Unable to connect or authenticate: " + e.getMessage());
            System.exit(0);
        }

        // Prepare Swing panel for painting & key‐capture
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);

        // 6) Start a thread that constantly reads GameState from 'in'
        new Thread(() -> {
            try {
                while (true) {
                    Object obj = in.readObject();
                    if (obj instanceof GameState) {
                        state = (GameState) obj;
                        // Once a winner is set, capture “You Win!” / “You Lose”
                        if (state.winner != 0 && gameOverMessage == null) {
                            gameOverMessage =
                              (state.winner == playerNumber) ? "You Win!" : "You Lose";
                        }
                        repaint();
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                JOptionPane.showMessageDialog(this, "Connection lost: " + e.getMessage());
                System.exit(0);
            }
        }).start();

        // 7) Swing timer to repaint at ~60 FPS
        new Timer(1000 / 60, e -> repaint()).start();
    }

    /** Called after construction to finalize any setup (none in this version). */
    private void start() {
        // All work is done in constructor threads & paintComponent
    }

    /** Set this client’s slot (1 or 2). */
    public void setPlayerNumber(int pNum) {
        this.playerNumber = pNum;
    }

    /** Returns the latest GameState from the server. */
    public GameState getState() {
        return state;
    }

    /** Send a paddle‐movement command (−1 = up, 1 = down, 0 = stop). */
    public void sendMovement(PlayerCommand cmd) {
        try {
            out.reset();
            out.writeObject(cmd);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Send a control command (READY, PAUSE, RESUME, RESTART). */
    public void sendControl(ControlCommand cc) {
        try {
            out.reset();
            out.writeObject(cc);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ─── paintComponent: draw waiting, playing, paused, or game‐over ─────────────
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING,
          RenderingHints.VALUE_ANTIALIAS_ON
        );

        int width  = getWidth();
        int height = getHeight();

        // 1) Waiting for both players to click READY
        if (!state.ready1 || !state.ready2) {
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 36));
            String waitingText = "Waiting for both players to be READY...";
            int textWidth = g2.getFontMetrics().stringWidth(waitingText);
            g2.drawString(waitingText, (width - textWidth) / 2, height / 2);
            return;
        }

        // 2) If game is over (winner != 0), overlay final screen
        if (state.winner != 0) {
            // Dim background
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRect(0, 0, width, height);

            // “You Win!” or “You Lose”
            g2.setColor((state.winner == playerNumber) ? Color.GREEN : Color.RED);
            g2.setFont(new Font("Consolas", Font.BOLD, 48));
            int w = g2.getFontMetrics().stringWidth(gameOverMessage);
            g2.drawString(gameOverMessage, (width - w) / 2, height / 2 - 20);

            // Show scores underneath
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 32));
            String scoreMsg = "P1: " + state.score1 + "    P2: " + state.score2;
            int sw = g2.getFontMetrics().stringWidth(scoreMsg);
            g2.drawString(scoreMsg, (width - sw) / 2, height / 2 + 30);
            return;
        }

        // 3) Draw center dashed line
        g2.setColor(Color.GRAY);
        Stroke dashed = new BasicStroke(
          2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{10}, 0
        );
        g2.setStroke(dashed);
        g2.drawLine(width / 2, 0, width / 2, height);
        g2.setStroke(new BasicStroke());

        // 4) Compute scale from 800×600 → current window size
        float xScale = width  / (float) GAME_WIDTH;
        float yScale = height / (float) GAME_HEIGHT;

        // 5) Draw paddles
        g2.setColor(Color.WHITE);
        int p1y = Math.round(state.paddle1Y * yScale);
        int p2y = Math.round(state.paddle2Y * yScale);
        int pw  = Math.round(PADDLE_WIDTH * xScale);
        int ph  = Math.round(PADDLE_HEIGHT * yScale);
        g2.fillRect(0, p1y, pw, ph);
        g2.fillRect(width - pw, p2y, pw, ph);

        // 6) Draw ball (only if not paused)
        if (!state.paused) {
            int bx = Math.round(state.ballX * xScale);
            int by = Math.round(state.ballY * yScale);
            int bs = Math.round(BALL_SIZE * xScale);
            g2.fillOval(bx, by, bs, bs);
        }

        // 7) Draw scores at top
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Consolas", Font.BOLD, 32));
        String s1 = "P1: " + state.score1;
        String s2 = "P2: " + state.score2;
        int s1w = g2.getFontMetrics().stringWidth(s1);
        g2.drawString(s1, (width / 2) - s1w - 20, 50);
        g2.drawString(s2, (width / 2) + 20, 50);

        // 8) If paused, overlay “PAUSE”
        if (state.paused) {
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRect(0, 0, width, height);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Consolas", Font.BOLD, 60));
            String pauseMsg = "PAUSE";
            int pwid = g2.getFontMetrics().stringWidth(pauseMsg);
            g2.drawString(pauseMsg, (width - pwid) / 2, height / 2);
        }
    }

    // ─── KeyListener: send UP/DOWN/W/S as PlayerCommand accordingly ─────────────
    @Override
    public void keyPressed(KeyEvent e) {
        if (!state.ready1 || !state.ready2 || state.paused || state.winner != 0) 
            return;
        int dir = 0;
        if (e.getKeyCode() == KeyEvent.VK_W || e.getKeyCode() == KeyEvent.VK_UP)   
            dir = -1;
        if (e.getKeyCode() == KeyEvent.VK_S || e.getKeyCode() == KeyEvent.VK_DOWN) 
            dir = 1;
        if (dir != 0) {
            lastCmd = new PlayerCommand(dir);
            sendMovement(lastCmd);
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (!state.ready1 || !state.ready2 || state.paused || state.winner != 0) 
            return;
        boolean relevant = 
            e.getKeyCode() == KeyEvent.VK_W   ||
            e.getKeyCode() == KeyEvent.VK_S   ||
            e.getKeyCode() == KeyEvent.VK_UP  ||
            e.getKeyCode() == KeyEvent.VK_DOWN;
        if (relevant) {
            lastCmd = new PlayerCommand(0);
            sendMovement(lastCmd);
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // unused
    }
}
