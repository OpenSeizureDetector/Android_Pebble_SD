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
            if (mViewPager.getCurrentItem() < mAdapter.getItemCount() - 1) {
                mViewPager.setCurrentItem(mViewPager.getCurrentItem() + 1);
            } else {
                finishOnboarding();
            }
        });
        
        mBtnBack.setOnClickListener(v -> {
            if (mViewPager.getCurrentItem() > 0) {
                mViewPager.setCurrentItem(mViewPager.getCurrentItem() - 1);
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
        updateNavigationButtons(0);



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
        
        // Mark first run as complete
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putBoolean("first_run_complete", true);
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
                case 0:
                    return new OnboardingWelcomeFragment();
                case 1:
                    return new OnboardingDataSourceFragment();
                case 2:
                    return new OnboardingDataSourceConfigFragment();
                case 3:
                    return new OnboardingAlgorithmsFragment();
                case 4:
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
