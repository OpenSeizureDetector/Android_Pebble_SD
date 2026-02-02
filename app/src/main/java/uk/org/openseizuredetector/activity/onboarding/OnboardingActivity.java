package uk.org.openseizuredetector.activity.onboarding;
import uk.org.openseizuredetector.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
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

import uk.org.openseizuredetector.activity.startup.StartupActivity;
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
    
    private OnboardingAdapter mAdapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        
        Log.i(TAG, "onCreate()");
        
        // Hide action bar for cleaner onboarding experience
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        
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
        
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 0:
                    return new OnboardingWelcomeFragment();
                case 1:
                    return new OnboardingDataSourceFragment();
                case 2:
                    return new OnboardingAlgorithmsFragment();
                case 3:
                    return new OnboardingCompleteFragment();
                default:
                    return new OnboardingWelcomeFragment();
            }
        }
        
        @Override
        public int getItemCount() {
            return 4; // Welcome, DataSource, Algorithms, Complete
        }
    }
    
    @Override
    public void onBackPressed() {
        if (mViewPager.getCurrentItem() == 0) {
            // If on first page, exit onboarding (but mark as incomplete)
            super.onBackPressed();
        } else {
            // Go to previous page
            mViewPager.setCurrentItem(mViewPager.getCurrentItem() - 1);
        }
    }
}
