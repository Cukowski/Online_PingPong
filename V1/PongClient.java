// File: PongClient.java
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import javax.swing.*;

/**
 * Client that connects to PongServer, sends paddle movement, and draws game state.
 */
public class PongClient extends JPanel implements KeyListener {
    private static final int WIDTH = 800, HEIGHT = 600;
    private static final int PADDLE_HEIGHT = 80, PADDLE_WIDTH = 10;
    private static final int BALL_SIZE = 15;

    // Network
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    // Game state received from server
    private volatile GameState state = new GameState();

    // Last command sent to server
    private PlayerCommand lastCmd = new PlayerCommand(0);

    // Which player am I? 1 or 2
    private int playerNumber;

    public static void main(String[] args) {
        String host = JOptionPane.showInputDialog("Enter server IP:", "localhost");
        String pNumStr = JOptionPane.showInputDialog("Are you player 1 or 2?", "1");
        int pNum = Integer.parseInt(pNumStr);
        JFrame frame = new JFrame("Networked Pong - Player " + pNum);
        PongClient client = new PongClient(host, 12345, pNum);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(WIDTH, HEIGHT);
        frame.add(client);
        frame.setResizable(false);
        frame.setVisible(true);
        client.start();
    }

    public PongClient(String host, int port, int playerNum) {
        this.playerNumber = playerNum;
        try {
            socket = new Socket(host, port);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Unable to connect to server.");
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
                        repaint();
                        if (state.winner != 0) {
                            String message = (state.winner == playerNumber) ? 
                                "You win!" : "You lose!";
                            JOptionPane.showMessageDialog(this, message);
                            System.exit(0);
                        }
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                JOptionPane.showMessageDialog(this, "Connection lost.");
                System.exit(0);
            }
        }).start();

        // Local timer to repaint at ~60 FPS
        new Timer(1000 / 60, e -> repaint()).start();
    }

    private void start() {
        // Nothing to do here; network listener and key events handle everything
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // Smooth rendering
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw middle line
        g2.setColor(Color.GRAY);
        Stroke dashed = new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{10}, 0);
        g2.setStroke(dashed);
        g2.drawLine(WIDTH/2, 0, WIDTH/2, HEIGHT);

        // Draw paddles
        g2.setStroke(new BasicStroke(1));
        g2.setColor(Color.WHITE);
        // Left paddle (player 1)
        g2.fillRect(0, state.paddle1Y, PADDLE_WIDTH, PADDLE_HEIGHT);
        // Right paddle (player 2)
        g2.fillRect(WIDTH - PADDLE_WIDTH, state.paddle2Y, PADDLE_WIDTH, PADDLE_HEIGHT);

        // Draw ball
        g2.fillOval(state.ballX, state.ballY, BALL_SIZE, BALL_SIZE);

        // Draw scores
        g2.setFont(new Font("Consolas", Font.BOLD, 36));
        g2.drawString(String.valueOf(state.score1), WIDTH/2 - 50, 50);
        g2.drawString(String.valueOf(state.score2), WIDTH/2 + 25, 50);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int dir = 0;
        if (playerNumber == 1) {
            if (e.getKeyCode() == KeyEvent.VK_W) dir = -1;
            if (e.getKeyCode() == KeyEvent.VK_S) dir = 1;
        } else {
            if (e.getKeyCode() == KeyEvent.VK_UP) dir = -1;
            if (e.getKeyCode() == KeyEvent.VK_DOWN) dir = 1;
        }
        lastCmd = new PlayerCommand(dir);
        sendCommand(lastCmd);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // On release, stop movement
        int dir = 0;
        if ((playerNumber == 1 && (e.getKeyCode() == KeyEvent.VK_W || e.getKeyCode() == KeyEvent.VK_S))
         || (playerNumber == 2 && (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN))) {
            lastCmd = new PlayerCommand(0);
            sendCommand(lastCmd);
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }

    private void sendCommand(PlayerCommand cmd) {
        try {
            out.reset();
            out.writeObject(cmd);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
