import org.junit.Test;
import java.util.regex.Pattern;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SdServiceConnectionTest {

    @Test
    public void isconnected_test() {
        assertThat(SdServiceConnection.isConnected(), is(true));
    }
    ...
}