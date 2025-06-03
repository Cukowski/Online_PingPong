// File: GameState.java
import java.io.Serializable;

/**
 * Encapsulates the positions of ball and paddles, 
 * velocities, and scores at any instant.
 */
public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;

    // Ball position and velocity
    public int ballX, ballY;
    public int ballDX, ballDY;
    
    // Paddle positions (y-coordinates)
    public int paddle1Y, paddle2Y;
    
    // Scores
    public int score1, score2;
    
    // Indicates if the game is over and who won (1 or 2), 0 if ongoing
    public int winner;

    public GameState() {
        ballX = ballY = 0;
        ballDX = ballDY = 0;
        paddle1Y = paddle2Y = 0;
        score1 = score2 = 0;
        winner = 0;
    }
}
