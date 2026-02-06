package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.graphics.Insets;

import uk.org.openseizuredetector.activity.startup.StartupActivity;
import uk.org.openseizuredetector.utils.OsdUtil;

/**
 * Onboarding wizard shown on first run to guide user through initial setup
 */
public class OnboardingActivity extends AppCompatActivity {
    private static final String TAG = "OnboardingActivity";
    
    // Fragment position constants
    private static final int FRAG_WELCOME = 0;
    private static final int FRAG_DATASOURCE = 1;
    private static final int FRAG_DATASOURCE_CONFIG = 2;
    private static final int FRAG_ALGORITHMS = 3;
    private static final int FRAG_COMPLETE = 4;

    // Key for saving ViewPager position
    private static final String SAVED_VIEWPAGER_POSITION = "viewpager_position";

    private ViewPager2 mViewPager;
    private Button mBtnNext;
    private Button mBtnBack;
    private Button mBtnSkip;
    private TabLayout mTabIndicator;
    private OsdUtil mUtil;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private OnboardingAdapter mAdapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        
        Log.i(TAG, "onCreate()");
        mUtil = new OsdUtil(this, mHandler);
        mUtil.writeToSysLogFile("OnboardingActivity.onCreate()", "LIFECYCLE");
        mUtil.writeMemoryLog("OnboardingActivity.onCreate");

        // Hide action bar for cleaner onboarding experience
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Handle system bars insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboarding_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        mViewPager = findViewById(R.id.onboarding_viewpager);
        mBtnNext = findViewById(R.id.btn_next);
        mBtnBack = findViewById(R.id.btn_back);
        mBtnSkip = findViewById(R.id.btn_skip);
        mTabIndicator = findViewById(R.id.tab_indicator);
        
        // Set up adapter with onboarding fragments
        mAdapter = new OnboardingAdapter(this);
        mViewPager.setAdapter(mAdapter);
        
        // Set up tab indicator dots
        new TabLayoutMediator(mTabIndicator, mViewPager,
                (tab, position) -> {
                    // Just show dots, no text
                }).attach();
        
        // Handle navigation buttons
        mBtnNext.setOnClickListener(v -> {
            int currentPosition = mViewPager.getCurrentItem();

            // Special handling for algorithm selection fragment
            if (currentPosition == FRAG_ALGORITHMS) {
                Fragment currentFragment = getSupportFragmentManager().findFragmentByTag("f" + currentPosition);
                if (currentFragment instanceof OnboardingAlgorithmsFragment) {
                    OnboardingAlgorithmsFragment algFragment = (OnboardingAlgorithmsFragment) currentFragment;
                    if (algFragment.handleNextClick()) {
                        // Fragment handled the click and wants to stay on current page
                        return;
                    }
                }
            }

            // If on DataSourceConfig and Network Data Source is selected, skip to Complete
            if (currentPosition == FRAG_DATASOURCE_CONFIG) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                String dataSource = prefs.getString("DataSource", "Phone");
                if ("Network".equals(dataSource)) {
                    // Skip algorithm selection, go straight to complete
                    mViewPager.setCurrentItem(FRAG_COMPLETE);
                    return;
                }
            }

            if (currentPosition < mAdapter.getItemCount() - 1) {
                mViewPager.setCurrentItem(currentPosition + 1);
            } else {
                finishOnboarding();
            }
        });
        
        mBtnBack.setOnClickListener(v -> {
            int currentPosition = mViewPager.getCurrentItem();

            // If on Complete (position 4) and Network Data Source is selected, skip to DataSourceConfig
            if (currentPosition == FRAG_COMPLETE) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                String dataSource = prefs.getString("DataSource", "Phone");
                if ("Network".equals(dataSource)) {
                    // Skip algorithm selection, go straight to config
                    mViewPager.setCurrentItem(FRAG_DATASOURCE_CONFIG);
                    return;
                }
            }

            if (currentPosition > FRAG_WELCOME) {
                mViewPager.setCurrentItem(currentPosition - 1);
            }
        });

        // handle the system back button
        getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);

        mBtnSkip.setOnClickListener(v -> finishOnboarding());
        
        // Update button visibility based on current page
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateNavigationButtons(position);
            }
        });
        
        // Initial button state
        updateNavigationButtons(FRAG_WELCOME);

    }

    @Override
    protected void onResume() {
        super.onResume();
        // Restore ViewPager position when returning from other activities (e.g., BLEScanActivity)
        // This ensures we return to the same fragment we were on before leaving
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int savedPosition = prefs.getInt(SAVED_VIEWPAGER_POSITION, FRAG_WELCOME);
        if (mViewPager.getCurrentItem() != savedPosition) {
            mViewPager.setCurrentItem(savedPosition, false);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save ViewPager position to restore if activity is recreated
        outState.putInt(SAVED_VIEWPAGER_POSITION, mViewPager.getCurrentItem());
        // Also save to preferences for when returning from other activities
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putInt(SAVED_VIEWPAGER_POSITION, mViewPager.getCurrentItem()).apply();
    }
    
    private void updateNavigationButtons(int position) {
        // Back button visible only if not on first page
        mBtnBack.setVisibility(position > 0 ? View.VISIBLE : View.INVISIBLE);
        
        // Update Next button text and Skip visibility on last page
        if (position == mAdapter.getItemCount() - 1) {
            mBtnNext.setText("Get Started");
            mBtnSkip.setVisibility(View.GONE);
        } else {
            mBtnNext.setText("Next");
            mBtnSkip.setVisibility(View.VISIBLE);
        }
    }
    
    private void finishOnboarding() {
        Log.i(TAG, "finishOnboarding() - marking first run as complete");
        
        // Mark first run as complete and clear ViewPager position for next run
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putBoolean("first_run_complete", true);
        editor.remove(SAVED_VIEWPAGER_POSITION); // Clear position so next time starts from welcome
        editor.apply();
        
        // Launch StartupActivity
        Intent intent = new Intent(this, StartupActivity.class);
        startActivity(intent);
        finish();
    }
    
    /**
     * Adapter for onboarding wizard pages
     */
    private class OnboardingAdapter extends FragmentStateAdapter {
        
        public OnboardingAdapter(FragmentActivity fa) {
            super(fa);
        }
        
        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case FRAG_WELCOME:
                    return new OnboardingWelcomeFragment();
                case FRAG_DATASOURCE:
                    return new OnboardingDataSourceFragment();
                case FRAG_DATASOURCE_CONFIG:
                    return new OnboardingDataSourceConfigFragment();
                case FRAG_ALGORITHMS:
                    return new OnboardingAlgorithmsFragment();
                case FRAG_COMPLETE:
                    return new OnboardingCompleteFragment();
                default:
                    return new OnboardingWelcomeFragment();
            }
        }
        
        @Override
        public int getItemCount() {
            return 5; // Welcome, DataSource, DataSourceConfig, Algorithms, Complete
        }
    }
    
    /*@Override
    public void onBackPressed() {
        if (mViewPager.getCurrentItem() == 0) {
            // If on first page, exit onboarding (but mark as incomplete)
            super.onBackPressed();
        } else {
            // Go to previous page
            mViewPager.setCurrentItem(mViewPager.getCurrentItem() - 1);
        }
    }

     */

    OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (mViewPager.getCurrentItem() > 0) {
                // Navigate to the previous page
                mViewPager.setCurrentItem(mViewPager.getCurrentItem() - 1);
            } else {
                // If on the first page, disable this callback and trigger the default behavior
                this.setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
        if (mUtil != null) {
            mUtil.writeToSysLogFile("OnboardingActivity.onDestroy()", "LIFECYCLE");
            mUtil.writeMemoryLog("OnboardingActivity.onDestroy");
        }
    }

}
