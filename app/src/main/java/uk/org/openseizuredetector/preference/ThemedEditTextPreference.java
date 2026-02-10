package uk.org.openseizuredetector.preference;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.preference.EditTextPreference;

/**
 * Custom EditTextPreference that forces readable text colors in dialogs.
 * This works in conjunction with the AppTheme.PreferenceAlertDialog style
 * which forces black text on white background.
 */
public class ThemedEditTextPreference extends EditTextPreference {

    public ThemedEditTextPreference(Context context) {
        super(context);
    }

    public ThemedEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThemedEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ThemedEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
}



