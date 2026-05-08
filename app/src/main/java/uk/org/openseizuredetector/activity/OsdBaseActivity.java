package uk.org.openseizuredetector.activity;

import android.os.Build;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Base Activity to ensure consistent system bar styling (Edge-to-Edge) across the app.
 * All Activities that want to support transparent system bars should extend this class
 * or implement similar logic.
 */
public abstract class OsdBaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
    }

    /**
     * Configures the system bars to be transparent and content to extend edge-to-edge.
     * Uses correct light/dark mode for icons.
     */
    protected void configureSystemBars() {
        // Configure system bar appearance to be edge-to-edge
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean isLightMode = isLightTheme();
                controller.setAppearanceLightStatusBars(isLightMode);
                controller.setAppearanceLightNavigationBars(isLightMode);
            }
        }
    }

    /**
     * Check if the current theme is light mode.
     * Can be overridden by subclasses if they have specific needs.
     */
    protected boolean isLightTheme() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }
}

