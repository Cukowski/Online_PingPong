// File: PlayerCommand.java
import java.io.Serializable;

/**
 * Sent from client to server to indicate paddle movement.
 * direction: -1 = up, 1 = down, 0 = no movement.
 */
public class PlayerCommand implements Serializable {
    private static final long serialVersionUID = 1L;
    public int direction;
    public PlayerCommand(int dir) {
        this.direction = dir;
    }
}
