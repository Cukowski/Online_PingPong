import java.io.Serializable;

/**
 * Sent from client to server for ready, pause, resume, or restart actions.
 */
public class ControlCommand implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        READY,
        PAUSE,
        RESUME,
        RESTART
    }

    public Type type;

    public ControlCommand(Type type) {
        this.type = type;
    }
}